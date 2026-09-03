package com.staysync.booking.calendar;

import com.staysync.booking.InventoryService;
import com.staysync.pricing.DayRate;
import com.staysync.pricing.RateBounds;
import com.staysync.pricing.RateCalendarEditor;
import com.staysync.pricing.RateCalendarView;
import com.staysync.pricing.RateChange;
import com.staysync.pricing.RateContext;
import com.staysync.pricing.RateEngine;
import com.staysync.pricing.RateRule;
import com.staysync.property.UnitCatalog;
import com.staysync.property.UnitSummary;
import com.staysync.shared.audit.AuditRecorder;
import com.staysync.shared.outbox.OutboxRecorder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요금·제약 일괄 편집의 <b>트랜잭션 경계</b>.
 *
 * <p>{@link BulkEditService} 가 락을 잡은 뒤 이 빈을 부른다. 둘을 나눈 이유는
 * {@code BookingService}/{@code ReservationWriter} 와 같다 — 락을 먼저 잡고 그 안에서
 * 트랜잭션을 열어야 하고, 같은 클래스 안에서 {@code @Transactional} 을 부르면 프록시를
 * 거치지 않아 트랜잭션이 아예 걸리지 않는다.
 *
 * <p><b>요금과 판매중지가 한 트랜잭션이다.</b> 출처가 다르다 — 요금·최소숙박·체크인금지는
 * {@code rate_calendar}(pricing), 판매중지는 {@code inventory_ledger}(booking) 다.
 * [적용] 한 번이 절반만 반영되면 화면과 원장이 어긋나고, 그 화면은 정상으로 보인다.
 * 감사 기록도 같은 트랜잭션이다.
 *
 * <p><b>여기가 pricing 이 아니라 booking 인 이유:</b> 일괄 편집은 요금(pricing)과
 * 판매중지(booking)를 한 번에 바꾼다. pricing 에 두면 pricing → booking 참조가 생기고,
 * 7주차 조립부가 만든 booking → pricing 과 맞물려 <b>순환이 된다.</b>
 * {@code ModularityTest} 가 잡을 것이고 잡는 게 맞다. 작업지시 06 의 5절 1번.
 */
@Component
class BulkEditWriter {

    /** 감사 로그와 이벤트의 대상 종류. 예약이 {@code RESERVATION} 인 것과 같은 자리다. */
    public static final String AGGREGATE_TYPE = "RATE_CALENDAR";

    /** 요금·제약이 바뀌었다는 사건. 실시간 갱신이 듣고, P3 에서 채널 전파가 듣는다. */
    public static final String BULK_EDITED = "RATE_BULK_EDITED";

    private final UnitCatalog unitCatalog;
    private final RateCalendarView rateView;
    private final RateCalendarEditor rateEditor;
    private final InventoryService inventoryService;
    private final AuditRecorder audit;
    private final OutboxRecorder outbox;

    BulkEditWriter(UnitCatalog unitCatalog,
                   RateCalendarView rateView,
                   RateCalendarEditor rateEditor,
                   InventoryService inventoryService,
                   AuditRecorder audit,
                   OutboxRecorder outbox) {
        this.unitCatalog = unitCatalog;
        this.rateView = rateView;
        this.rateEditor = rateEditor;
        this.inventoryService = inventoryService;
        this.audit = audit;
        this.outbox = outbox;
    }

    /**
     * 미리보기와 적용이 <b>같은 경로</b>다.
     *
     * <p>바뀔 셀을 먼저 다 계산하고, {@code dryRun} 이면 그 수만 돌려주고 끝낸다.
     * 미리보기를 별도 엔드포인트로 만들면 언젠가 갈라지고, 갈라진 순간의 증상은
     * "미리보기에 20건이라 했는데 22건이 바뀌었다"이다. 사용자가 적용한 뒤에야 안다.
     */
    @Transactional
    BulkEdit.Result apply(Long propertyId, BulkEdit.Request request) {
        List<UnitSummary> units = targetUnits(propertyId, request);
        List<LocalDate> dates = targetDates(request);

        List<RateChange> rateChanges = request.touchesRates()
                ? planRateChanges(units, dates, request)
                : List.of();

        BulkEdit.Result result = new BulkEdit.Result(
                units.size(), dates.size(), units.size() * dates.size(),
                changedFields(request), request.dryRun());

        if (request.dryRun()) {
            // 요금도 원장도 감사 로그도 건드리지 않는다. 여기서 끝내는 것이 전부다.
            return result;
        }

        if (!rateChanges.isEmpty()) {
            rateEditor.applyAll(rateChanges);
        }
        if (request.stopSell() != null) {
            for (UnitSummary unit : units) {
                // 재고 변경은 전부 InventoryService 를 거친다. 판매중지도 예외가 아니다.
                inventoryService.changeStopSell(unit.id(), dates, request.stopSell());
            }
        }

        recordAudit(propertyId, request, result);
        recordEvent(propertyId, result);
        return result;
    }

    /**
     * 대상 판매 단위를 읽는다.
     *
     * <p>요청에 담긴 식별자가 <b>이 숙소의 것인지</b> 여기서 걸러진다. 조직 소유 확인은
     * 컨트롤러가 {@code OwnedResources} 로 이미 했고, 여기는 그 숙소 안인지를 본다.
     * 둘 다 있어야 다른 조직의 판매 단위를 적용 대상에 끼워 넣을 수 없다.
     */
    private List<UnitSummary> targetUnits(Long propertyId, BulkEdit.Request request) {
        Map<Long, UnitSummary> byId = new HashMap<>();
        for (UnitSummary unit : unitCatalog.summariesOf(propertyId)) {
            byId.put(unit.id(), unit);
        }

        List<UnitSummary> targets = new ArrayList<>();
        for (Long unitId : request.unitIds()) {
            UnitSummary unit = byId.get(unitId);
            if (unit == null) {
                // 남의 것인지 이 숙소에 없는 것인지 구분해 알려 주지 않는다. 3주차와 같다.
                throw new com.staysync.property.UnitNotFoundException(unitId);
            }
            targets.add(unit);
        }
        return targets;
    }

    /** 요일 필터를 거친 날짜. 양끝을 포함한다. */
    private List<LocalDate> targetDates(BulkEdit.Request request) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = request.from();
             !date.isAfter(request.to());
             date = date.plusDays(1)) {
            if (request.matchesWeekday(date)) {
                dates.add(date);
            }
        }
        if (dates.isEmpty()) {
            throw new InvalidBulkEditException("요일 필터를 적용하면 해당하는 날짜가 없습니다.");
        }
        return dates;
    }

    /**
     * 셀마다 바뀔 값을 만든다.
     *
     * <p><b>기준가를 셀마다 따로 읽는다.</b> "기존 대비 +20%" 는 그 셀의 현재 요금에
     * 붙어야 한다. 한 번 읽어 전부에 같은 값을 쓰면 요일마다 다르게 매겨 둔 요금이
     * 하나로 뭉개지고, 화면에는 정상으로 보인다.
     *
     * <p>요금 행이 없는 날은 {@code unit.base_price} 가 기준가다. 조회에서 빈 셀을
     * 채우는 규칙과 같아야 한다 — 다르면 화면에 보이던 값과 다른 값에 %가 붙는다.
     */
    private List<RateChange> planRateChanges(List<UnitSummary> units, List<LocalDate> dates,
                                             BulkEdit.Request request) {
        Map<Long, DayRate> existing = existingRates(units, dates);

        List<RateChange> changes = new ArrayList<>(units.size() * dates.size());
        for (UnitSummary unit : units) {
            Long ratePlanId = unit.defaultRatePlanId();
            if (ratePlanId == null) {
                // 판매 단위 등록이 기본 요금제를 자동 생성하므로 보통 있다. 없으면
                // 요금을 쓸 곳이 없으니 조용히 넘기지 말고 드러낸다.
                throw new InvalidBulkEditException(
                        "판매 단위에 기본 요금제가 없어 요금을 바꿀 수 없습니다. unitId=" + unit.id());
            }
            for (LocalDate date : dates) {
                DayRate current = existing.get(cellKey(ratePlanId, date));
                changes.add(new RateChange(
                        ratePlanId, date,
                        newPrice(request, unit, current, date),
                        newMinStay(request, current),
                        newClosedToArrival(request, current)));
            }
        }
        return changes;
    }

    private Map<Long, DayRate> existingRates(List<UnitSummary> units, List<LocalDate> dates) {
        List<Long> ratePlanIds = units.stream()
                .map(UnitSummary::defaultRatePlanId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (ratePlanIds.isEmpty()) {
            return Map.of();
        }
        // 범위로 한 번에 읽는다. 셀마다 질의하면 30일 × 10단위가 300왕복이다.
        LocalDate from = dates.get(0);
        LocalDate to = dates.get(dates.size() - 1);

        Map<Long, DayRate> index = new HashMap<>();
        for (DayRate rate : rateView.ratesOf(ratePlanIds, from, to)) {
            index.put(cellKey(rate.ratePlanId(), rate.date()), rate);
        }
        return index;
    }

    /** 요금제와 날짜를 하나의 키로. 맵 두 겹보다 읽기 쉽다. */
    private static Long cellKey(Long ratePlanId, LocalDate date) {
        return ratePlanId * 1_000_000L + date.toEpochDay();
    }

    private BigDecimal newPrice(BulkEdit.Request request, UnitSummary unit,
                                DayRate current, LocalDate date) {
        BigDecimal base = current == null ? unit.basePrice() : current.price();
        return switch (request.price()) {
            case null -> base;                                  // 요금은 건드리지 않는다
            case BulkEdit.PriceChange.Fixed fixed -> fixed.price();
            case BulkEdit.PriceChange.Percent percent ->
                // 규칙 엔진을 호출하는 첫 사용처다. 규칙 하나짜리 호출인 셈이고,
                // 100원 단위 반올림도 엔진이 함께 해 준다.
                    RateEngine.price(base,
                            List.of(new RateRule.SeasonRule(
                                    request.from(), request.to(), percent.multiplier(), 0)),
                            RateContext.ofDate(date),
                            RateBounds.NONE);
        };
    }

    private short newMinStay(BulkEdit.Request request, DayRate current) {
        if (request.minStay() != null) {
            return request.minStay();
        }
        return current == null ? 1 : current.minStay();
    }

    private boolean newClosedToArrival(BulkEdit.Request request, DayRate current) {
        if (request.closedToArrival() != null) {
            return request.closedToArrival();
        }
        // DayRate 는 체크인 금지를 담지 않는다. 바꾸지 않는 요청에서 기존 값을 알 수
        // 없으므로 false 로 둔다. 지금 화면이 이 값을 쓰지 않아 드러나지 않지만,
        // 셀에 표시하게 되면 DayRate 에 필드를 더해야 한다.
        return false;
    }

    /**
     * 일괄 편집 한 번을 <b>한 줄로</b> 남긴다.
     *
     * <p>셀마다 남기면 30일 × 10단위가 300줄이다. 무엇을 했는지 읽어내기 어려워지고,
     * 정작 봐야 할 예약 변경 기록이 묻힌다. 범위와 바뀐 항목을 담으면 되돌아볼 때
     * 필요한 것은 다 있다.
     *
     * <p>연락처는 들어갈 자리가 없다. ADR 0007 의 선이 그대로다.
     */
    private void recordAudit(Long propertyId, BulkEdit.Request request, BulkEdit.Result result) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("unitIds", request.unitIds());
        after.put("from", request.from().toString());
        after.put("to", request.to().toString());
        after.put("weekdays", request.weekdays().stream().map(Enum::name).sorted().toList());
        after.put("cellCount", result.cellCount());
        after.put("changed", result.changed());
        if (request.price() instanceof BulkEdit.PriceChange.Fixed fixed) {
            after.put("price", fixed.price());
        }
        if (request.price() instanceof BulkEdit.PriceChange.Percent percent) {
            after.put("priceRate", percent.rate());
        }
        if (request.minStay() != null) {
            after.put("minStay", request.minStay());
        }
        if (request.closedToArrival() != null) {
            after.put("closedToArrival", request.closedToArrival());
        }
        if (request.stopSell() != null) {
            after.put("stopSell", request.stopSell());
        }

        // 전 값은 담지 않는다. 셀 300개의 이전 요금을 한 줄에 넣으면 그건 요약이 아니다.
        audit.record(AGGREGATE_TYPE, propertyId, "RATE_BULK_EDIT", null, after);
    }

    /**
     * 요금·제약이 바뀌었다는 사건을 남긴다. 같은 트랜잭션이다.
     *
     * <p>지금 듣는 것은 실시간 갱신이고, P3 에서 채널 전파가 옆에 붙는다. 페이로드에는
     * <b>식별자와 범위만</b> 담는다 — 화면은 이걸 받고 캘린더를 다시 조회하지 값을
     * 이벤트에서 읽지 않는다. 바뀐 셀을 전부 실으면 30일 × 10단위가 페이로드 하나에
     * 들어간다.
     */
    private void recordEvent(Long propertyId, BulkEdit.Result result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("propertyId", propertyId);
        payload.put("cellCount", result.cellCount());
        payload.put("changed", result.changed());
        outbox.record(AGGREGATE_TYPE, propertyId, BULK_EDITED, payload);
    }

    private static List<String> changedFields(BulkEdit.Request request) {
        List<String> changed = new ArrayList<>();
        if (request.price() != null) {
            changed.add("price");
        }
        if (request.minStay() != null) {
            changed.add("minStay");
        }
        if (request.closedToArrival() != null) {
            changed.add("closedToArrival");
        }
        if (request.stopSell() != null) {
            changed.add("stopSell");
        }
        return changed;
    }
}
