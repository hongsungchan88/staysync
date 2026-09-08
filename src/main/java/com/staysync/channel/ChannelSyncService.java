package com.staysync.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.staysync.booking.DailyAvailability;
import com.staysync.booking.InventoryService;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.ChannelMapping;
import com.staysync.channel.port.Capability;
import com.staysync.pricing.DayRate;
import com.staysync.pricing.RateCalendarView;
import com.staysync.property.UnitCatalog;
import com.staysync.property.UnitSummary;
import com.staysync.shared.outbox.DomainEventPublisher;
import com.staysync.shared.outbox.OutboxEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox 소비자 자리의 <b>두 번째 입주자</b>다. 계획서 6.5 송신 경로의 윗단.
 *
 * <p>첫 입주자는 9주차의 실시간 갱신({@code CalendarEventBroadcaster})이었다. 여기는
 * 같은 사건을 듣고 채널로 내보낸다.
 *
 * <pre>
 *   RATE_BULK_EDITED       → 요금·제약이 바뀌었다 → PUSH_RATE 지원 연결로
 *   RESERVATION_HELD       → 재고가 줄었다(임시)  → PUSH_AVAILABILITY 지원 연결로
 *   RESERVATION_CONFIRMED  → 재고가 줄었다        → PUSH_AVAILABILITY 지원 연결로
 *   RESERVATION_CANCELLED  → 재고가 늘었다              "
 *   RESERVATION_EXPIRED    → 재고가 늘었다              "
 *   RESERVATION_DATES_CHANGED → 양쪽 기간이 바뀌었다    "
 * </pre>
 *
 * <p><b>이벤트에서 값을 읽지 않고 현재 값을 다시 읽는다.</b> 페이로드에는 식별자와
 * 범위만 있다. 이렇게 하면 이벤트가 중복 전달돼도(최소 1회 전달) 같은 현재 값을 두 번
 * 보내게 되어 결과가 같다. 페이로드의 값을 그대로 보내면 오래된 이벤트가 나중에
 * 처리될 때 <b>옛 값이 새 값을 덮어쓴다.</b>
 *
 * <p><b>{@code capabilities()} 가 실제로 무언가를 거르는 첫 자리다.</b> 계획서 6.1 이
 * 말하는 설계의 요점이 여기서 동작한다 — iCal 은 {@code PUSH_RATE} 를 지원하지 않으므로
 * 요금을 바꿔도 그 연결에는 작업이 만들어지지 않는다(13주차에 실제로 걸린다).
 *
 * <p><b>예약을 만든 채널 자신에게는 되보내지 않는다.</b> 계획서 6.5 수신 경로의 5번이
 * "다른 채널로 재고 차감 전파"인 이유다. 자기가 판 예약을 자기에게 다시 알리는 것은
 * 낭비이고, 채널에 따라서는 되울림이 된다.
 *
 * <p>예외를 삼키지 않는다. {@code OutboxRelay} 가 실패로 기록하고 다음 주기에 다시
 * 준다. 조용히 성공으로 처리하면 채널이 옛 값을 든 채로 남고 아무 증상이 없다.
 */
@Component
class ChannelSyncService implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChannelSyncService.class);

    /** 9주차 일괄 편집이 남기는 사건. {@code BulkEditWriter.BULK_EDITED} 와 같은 값이다. */
    private static final String RATE_BULK_EDITED = "RATE_BULK_EDITED";

    /** 재고를 움직이는 예약 사건들. 이름은 {@code ReservationEvents} 와 같은 값이다. */
    private static final List<String> INVENTORY_EVENTS = List.of(
            "RESERVATION_CONFIRMED",
            "RESERVATION_CANCELLED",
            "RESERVATION_EXPIRED",
            "RESERVATION_DATES_CHANGED",
            // P4 16주차. 홀드도 재고를 차지한다 — available() 이 held 를 빼기 때문이다.
            // 이것이 빠져 있어 만료(재고 증가)만 나가고 생성(재고 감소)은 안 나갔다.
            "RESERVATION_HELD");

    private final ChannelConnectionRepository connections;
    private final ChannelMappingRepository mappings;
    private final ChannelAdapterRegistry registry;
    private final AriCoalescingBuffer buffer;
    private final UnitCatalog unitCatalog;
    private final InventoryService inventory;
    private final RateCalendarView rates;
    private final ObjectMapper json;

    ChannelSyncService(ChannelConnectionRepository connections,
                       ChannelMappingRepository mappings,
                       ChannelAdapterRegistry registry,
                       AriCoalescingBuffer buffer,
                       UnitCatalog unitCatalog,
                       InventoryService inventory,
                       RateCalendarView rates,
                       ObjectMapper json) {
        this.connections = connections;
        this.mappings = mappings;
        this.registry = registry;
        this.buffer = buffer;
        this.unitCatalog = unitCatalog;
        this.inventory = inventory;
        this.rates = rates;
        this.json = json;
    }

    @Override
    public void publish(OutboxEvent event) {
        String type = event.getEventType();
        if (RATE_BULK_EDITED.equals(type)) {
            onRateChanged(read(event));
        } else if (INVENTORY_EVENTS.contains(type)) {
            onInventoryChanged(read(event));
        }
        // 나머지는 이 소비자와 관계없는 사건이다. 조용히 넘긴다.
    }

    /** 요금·제약 변경. {@code PUSH_RATE} 를 지원하는 연결에만 나간다. */
    private void onRateChanged(JsonNode payload) {
        List<Long> unitIds = longs(payload.get("unitIds"));
        LocalDate from = date(payload, "from");
        LocalDate to = date(payload, "to");
        if (unitIds.isEmpty() || from == null || to == null) {
            log.warn("일괄 편집 이벤트에 범위가 없어 전파하지 않는다. payload={}", payload);
            return;
        }
        for (Long unitId : unitIds) {
            propagate(unitId, from, to, Capability.PUSH_RATE, null);
        }
    }

    /**
     * 재고 변경. {@code PUSH_AVAILABILITY} 를 지원하는 연결에만 나간다.
     *
     * <p>예약 하나가 움직이면 그 기간의 재고가 통째로 바뀐다. 체크아웃일은 재고를
     * 차지하지 않으므로({@code StayPeriod} 가 배타적이다) 마지막 밤까지만 보낸다.
     */
    private void onInventoryChanged(JsonNode payload) {
        Long unitId = asLong(payload.get("unitId"));
        LocalDate checkIn = date(payload, "checkIn");
        LocalDate checkOut = date(payload, "checkOut");
        if (unitId == null || checkIn == null || checkOut == null) {
            log.warn("예약 이벤트에 판매 단위나 기간이 없어 전파하지 않는다. payload={}", payload);
            return;
        }
        String origin = payload.hasNonNull("channelCode") ? payload.get("channelCode").asText() : null;
        propagate(unitId, checkIn, checkOut.minusDays(1), Capability.PUSH_AVAILABILITY, origin);
    }

    /**
     * 한 판매 단위의 한 기간을 매핑된 연결들에 밀어 넣는다.
     *
     * @param required   이 기능을 지원하지 않는 연결은 건너뛴다
     * @param originChannel 이 채널 코드의 연결에는 보내지 않는다. 자기가 만든 예약이다
     */
    private void propagate(Long unitId, LocalDate from, LocalDate to,
                           Capability required, String originChannel) {
        List<ChannelMapping> unitMappings = mappings.findByUnitId(unitId);
        if (unitMappings.isEmpty()) {
            // 매핑되지 않은 판매 단위는 어느 채널에도 나가지 않는다. 10주차 화면이
            // 그걸 눈에 띄게 표시하는 이유다.
            return;
        }

        Map<Long, ChannelConnection> byId = connectionsOf(unitMappings);
        List<DailyAvailability> availability = null;
        Map<LocalDate, DayRate> rateByDate = null;

        for (ChannelMapping mapping : unitMappings) {
            ChannelConnection connection = byId.get(mapping.getConnectionId());
            if (connection == null || !connection.isSyncEnabled()) {
                continue;
            }
            if (!registry.capabilitiesOf(connection.getAdapterType()).contains(required)) {
                // 계획서 6.1 의 요점이 동작하는 자리. iCal 은 요금을 보낼 수 없으므로
                // 요금이 바뀌어도 전파 작업을 만들지 않는다.
                log.debug("{} 를 지원하지 않는 연결이라 건너뛴다. connectionId={} adapter={}",
                        required, connection.getId(), connection.getAdapterType());
                continue;
            }
            if (originChannel != null && originChannel.equals(connection.getChannelCode())) {
                // 이 예약을 만든 채널이다. 자기에게 되보내지 않는다.
                continue;
            }

            // 읽기는 보낼 곳이 하나라도 있을 때만 한다. 매핑은 있지만 전부 걸러지는
            // 경우가 흔하고(iCal 에 요금 변경), 그때 원장과 요금을 읽을 이유가 없다.
            if (required == Capability.PUSH_AVAILABILITY && availability == null) {
                availability = readAvailability(unitId, from, to);
            }
            if (required == Capability.PUSH_RATE && rateByDate == null) {
                rateByDate = readRates(unitId, from, to);
            }
            enqueue(connection, mapping, from, to, availability, rateByDate);
        }
    }

    private void enqueue(ChannelConnection connection, ChannelMapping mapping,
                         LocalDate from, LocalDate to,
                         List<DailyAvailability> availability,
                         Map<LocalDate, DayRate> rateByDate) {
        if (availability != null) {
            for (DailyAvailability day : availability) {
                buffer.enqueue(connection.getId(), mapping.getExternalUnitId(),
                        mapping.getExternalRateId(), day.date(),
                        day.available(), null, null, day.stopSell());
            }
            return;
        }
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            DayRate rate = rateByDate.get(date);
            if (rate == null) {
                // 요금 행이 없는 날이다. 캘린더는 unit.base_price 로 떨어지지만 여기서
                // 그 값을 보내면, 바꾸지도 않은 날의 요금을 채널에 새로 쓰는 것이 된다.
                continue;
            }
            buffer.enqueue(connection.getId(), mapping.getExternalUnitId(),
                    mapping.getExternalRateId(), date,
                    null, rate.price(), (int) rate.minStay(), null);
        }
    }

    private List<DailyAvailability> readAvailability(Long unitId, LocalDate from, LocalDate to) {
        UnitSummary unit = unitCatalog.summaryOf(unitId);
        return inventory.availabilityByDate(unitId, from, to, unit.totalUnits());
    }

    private Map<LocalDate, DayRate> readRates(Long unitId, LocalDate from, LocalDate to) {
        Long ratePlanId = unitCatalog.summaryOf(unitId).defaultRatePlanId();
        Map<LocalDate, DayRate> index = new HashMap<>();
        if (ratePlanId == null) {
            // 판매 단위 등록이 기본 요금제를 자동 생성하므로 보통 있다. 없으면 보낼
            // 요금이 없는 것이고, 그건 오류가 아니라 아직 요금을 매기지 않은 상태다.
            return index;
        }
        for (DayRate rate : rates.ratesOf(List.of(ratePlanId), from, to)) {
            index.put(rate.date(), rate);
        }
        return index;
    }

    private Map<Long, ChannelConnection> connectionsOf(List<ChannelMapping> unitMappings) {
        List<Long> ids = unitMappings.stream().map(ChannelMapping::getConnectionId).distinct().toList();
        Map<Long, ChannelConnection> byId = new HashMap<>();
        for (ChannelConnection connection : connections.findAllById(ids)) {
            byId.put(connection.getId(), connection);
        }
        return byId;
    }

    // --- 페이로드 읽기 -----------------------------------------------------------

    private JsonNode read(OutboxEvent event) {
        try {
            return json.readTree(event.getPayload());
        } catch (Exception e) {
            // 삼키지 않는다. 릴레이가 실패로 기록하고 다시 준다.
            throw new IllegalStateException(
                    "이벤트 페이로드를 읽지 못했습니다. eventId=" + event.getId(), e);
        }
    }

    private static List<Long> longs(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Long> values = new ArrayList<>();
        node.forEach(element -> values.add(element.asLong()));
        return values;
    }

    private static Long asLong(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static LocalDate date(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : LocalDate.parse(node.asText());
    }
}
