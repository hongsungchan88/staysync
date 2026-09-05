package com.staysync.booking.calendar;

import com.staysync.booking.GuestRepository;
import com.staysync.booking.InventoryLedgerRepository;
import com.staysync.booking.OverbookingConflictRepository;
import com.staysync.booking.ReservationRepository;
import com.staysync.booking.domain.Guest;
import com.staysync.booking.domain.InventoryLedger;
import com.staysync.booking.domain.OverbookingConflict;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.ReservationStatus;
import com.staysync.pricing.DayRate;
import com.staysync.pricing.RateCalendarView;
import com.staysync.property.UnitCatalog;
import com.staysync.property.UnitSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캘린더 그리드를 조립한다.
 *
 * <p><b>범위 쿼리 세 번이다.</b> 셀마다 질의하지 않는다는 것이 계획서 12.2 가 말하는
 * 최적화이지 SQL 문장을 하나로 만들라는 뜻이 아니다. 각 모듈이 자기 테이블만 한 번씩
 * 범위로 읽고 여기서 조립한다.
 *
 * <pre>
 *   inventory_ledger   booking   범위 스캔
 *   rate_calendar      pricing   범위 스캔 (RateCalendarView)
 *   reservation        booking   기간 겹침
 * </pre>
 *
 * <p>한 문장으로 조인하면 왕복이 하나 줄지만 한 모듈이 다른 모듈의 테이블을 직접 읽게
 * 된다. {@code ModularityTest} 는 자바 패키지 참조를 보므로 그런 위반을 잡지 못한다.
 * 테스트가 못 잡는 위반은 다음에 또 나오고 그때는 근거가 "지난번에도 했으니까"가 된다.
 * 성능이 목표에 못 미치면 측정값을 근거로 다시 연다.
 *
 * <p>property 와 pricing 에서 쓰는 것은 최상위 패키지에 공개된 {@link UnitCatalog},
 * {@link RateCalendarView} 와 그 값 타입뿐이다.
 */
@Service
@Transactional(readOnly = true)
public class CalendarService {

    /**
     * 조회 가능한 최대 기간. 계획서 8.2 가 잡은 값이다.
     *
     * <p>상한이 없으면 한 번의 요청으로 원장 전체를 긁을 수 있다.
     */
    static final int MAX_DAYS = 365;

    /** 그리드에 막대로 그릴 예약 상태. 취소·만료된 예약은 화면에 남지 않는다. */
    private static final List<ReservationStatus> VISIBLE = List.of(
            ReservationStatus.HOLD,
            ReservationStatus.CONFIRMED,
            ReservationStatus.CHECKED_IN,
            ReservationStatus.CHECKED_OUT,
            ReservationStatus.NO_SHOW);

    private final UnitCatalog unitCatalog;
    private final RateCalendarView rateCalendarView;
    private final InventoryLedgerRepository ledgerRepo;
    private final ReservationRepository reservationRepo;
    private final GuestRepository guestRepo;
    private final OverbookingConflictRepository conflictRepo;

    CalendarService(UnitCatalog unitCatalog,
                    RateCalendarView rateCalendarView,
                    InventoryLedgerRepository ledgerRepo,
                    ReservationRepository reservationRepo,
                    GuestRepository guestRepo,
                    OverbookingConflictRepository conflictRepo) {
        this.unitCatalog = unitCatalog;
        this.rateCalendarView = rateCalendarView;
        this.ledgerRepo = ledgerRepo;
        this.reservationRepo = reservationRepo;
        this.guestRepo = guestRepo;
        this.conflictRepo = conflictRepo;
    }

    /**
     * 그리드를 만든다.
     *
     * <p>조직 스코핑은 이 메서드가 하지 않는다. 컨트롤러가 {@code OwnedResources} 로
     * 먼저 확인하고 통과한 뒤에 부른다. 스코핑과 조회 최적화를 얽지 않기 위해서다.
     */
    public CalendarGrid assemble(Long propertyId, LocalDate from, LocalDate to) {
        validateRange(from, to);

        List<UnitSummary> units = unitCatalog.summariesOf(propertyId);
        if (units.isEmpty()) {
            // 판매 단위가 없는 숙소다. 화면의 빈 상태가 이걸 받는다.
            return new CalendarGrid(from, to, List.of(), List.of());
        }

        List<Long> unitIds = units.stream().map(UnitSummary::id).toList();
        List<Long> ratePlanIds = units.stream()
                .map(UnitSummary::defaultRatePlanId)
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<CellKey, InventoryLedger> ledger = indexLedger(unitIds, from, to);
        Map<CellKey, DayRate> rates = indexRates(units, ratePlanIds, from, to);
        Set<CellKey> conflicts = indexConflicts(propertyId, from, to);

        List<CalendarGrid.UnitRow> rows = units.stream()
                .map(unit -> toRow(unit, from, to, ledger, rates, conflicts))
                .toList();

        return new CalendarGrid(from, to, rows, reservationBars(propertyId, from, to));
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new InvalidCalendarRangeException("조회 기간을 지정해야 합니다.");
        }
        if (to.isBefore(from)) {
            throw new InvalidCalendarRangeException("종료일이 시작일보다 앞설 수 없습니다.");
        }
        // 양끝을 포함하므로 하루짜리 조회는 1일이다.
        long days = to.toEpochDay() - from.toEpochDay() + 1;
        if (days > MAX_DAYS) {
            throw new InvalidCalendarRangeException(
                    "조회 기간은 최대 %d일입니다. 요청한 기간은 %d일입니다.".formatted(MAX_DAYS, days));
        }
    }

    private Map<CellKey, InventoryLedger> indexLedger(List<Long> unitIds,
                                                      LocalDate from, LocalDate to) {
        Map<CellKey, InventoryLedger> index = new HashMap<>();
        for (InventoryLedger row : ledgerRepo.findGrid(unitIds, from, to)) {
            index.put(new CellKey(row.getUnitId(), row.getStayDate()), row);
        }
        return index;
    }

    /** 요금은 요금제 단위로 오므로 판매 단위 키로 바꿔 둔다. */
    private Map<CellKey, DayRate> indexRates(List<UnitSummary> units, List<Long> ratePlanIds,
                                             LocalDate from, LocalDate to) {
        Map<Long, Long> unitIdByRatePlanId = new HashMap<>();
        for (UnitSummary unit : units) {
            if (unit.defaultRatePlanId() != null) {
                unitIdByRatePlanId.put(unit.defaultRatePlanId(), unit.id());
            }
        }

        Map<CellKey, DayRate> index = new HashMap<>();
        for (DayRate rate : rateCalendarView.ratesOf(ratePlanIds, from, to)) {
            Long unitId = unitIdByRatePlanId.get(rate.ratePlanId());
            if (unitId != null) {
                index.put(new CellKey(unitId, rate.date()), rate);
            }
        }
        return index;
    }

    /**
     * 미해소 충돌이 있는 셀. <b>범위 쿼리 한 번</b>이다.
     *
     * <p>7주차에 세 번이던 것이 네 번이 됐다. 셀마다 묻지 않는다는 규칙은 그대로다 —
     * 셀마다 물으면 30일 × 10단위가 300왕복이다.
     */
    private Set<CellKey> indexConflicts(Long propertyId, LocalDate from, LocalDate to) {
        Set<CellKey> keys = new HashSet<>();
        for (OverbookingConflict conflict : conflictRepo.findOpenIn(propertyId, from, to)) {
            keys.add(new CellKey(conflict.getUnitId(), conflict.getStayDate()));
        }
        return keys;
    }

    private CalendarGrid.UnitRow toRow(UnitSummary unit, LocalDate from, LocalDate to,
                                       Map<CellKey, InventoryLedger> ledger,
                                       Map<CellKey, DayRate> rates,
                                       Set<CellKey> conflicts) {
        List<CalendarGrid.DayCell> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            CellKey key = new CellKey(unit.id(), date);
            days.add(toCell(unit, date, ledger.get(key), rates.get(key), conflicts.contains(key)));
        }
        return new CalendarGrid.UnitRow(unit.id(), unit.name(), unit.totalUnits(), days);
    }

    private CalendarGrid.DayCell toCell(UnitSummary unit, LocalDate date,
                                        InventoryLedger row, DayRate rate, boolean conflict) {
        // 원장에 행이 없는 날은 아직 아무도 예약하지 않은 날이다. 재고가 0 인 것과 전혀
        // 다르다. 둘을 같게 다루면 빈 그리드가 전부 매진으로 보인다.
        int avail = row == null ? unit.totalUnits() : row.available();
        boolean stopSell = row != null && row.isStopSell();

        // 요금이 없는 날은 판매 단위의 기본 요금으로 떨어진다. 이 채우기를 pricing 에
        // 두면 pricing 이 unit.base_price 를 알아야 해 pricing → property 의존이 생긴다.
        BigDecimal price = rate == null ? unit.basePrice() : rate.price();
        short minStay = rate == null ? 1 : rate.minStay();

        // 충돌은 P3 12주차부터 실제 값이다. 채널 수신이 재고를 넘겨 받아들이면
        // overbooking_conflict 에 행이 생기고 그 셀이 여기서 true 가 된다.
        // 7주차에 필드만 두고 "P3 에서 채운다"고 적어 둔 자리다.
        return new CalendarGrid.DayCell(date, avail, price, minStay, stopSell, conflict);
    }

    /**
     * 기간에 걸친 예약을 막대로 만든다.
     *
     * <p>{@code findOverlapping} 이 {@code checkIn < to AND checkOut > from} 이라
     * 체크인이 {@code from} 이전이고 체크아웃이 {@code to} 이후인 예약도 포함된다.
     * 화면을 가로지르는 막대가 경계에서 사라지면 안 된다.
     */
    private List<CalendarGrid.ReservationBar> reservationBars(Long propertyId,
                                                              LocalDate from, LocalDate to) {
        List<Reservation> reservations = reservationRepo.findOverlapping(
                propertyId, from, to.plusDays(1), VISIBLE);

        Map<Long, String> guestNames = guestNames(reservations);

        return reservations.stream()
                .map(r -> new CalendarGrid.ReservationBar(
                        r.getId(), r.getUnitId(),
                        r.getPeriod().checkIn(), r.getPeriod().checkOut(),
                        r.getGuestId() == null ? null : guestNames.get(r.getGuestId()),
                        r.getChannelCode(), r.getStatus().name(), r.getTotalAmount()))
                .toList();
    }

    /** 게스트 이름을 한 번에 읽는다. 예약마다 조회하면 막대 수만큼 왕복이 는다. */
    private Map<Long, String> guestNames(List<Reservation> reservations) {
        List<Long> guestIds = reservations.stream()
                .map(Reservation::getGuestId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (guestIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Guest guest : guestRepo.findAllById(guestIds)) {
            names.put(guest.getId(), guest.getName());
        }
        return names;
    }

    /** 판매 단위와 날짜로 셀을 찾는 키. */
    private record CellKey(Long unitId, LocalDate date) {
    }
}
