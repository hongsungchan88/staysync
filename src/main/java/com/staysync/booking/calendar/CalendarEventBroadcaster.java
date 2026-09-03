package com.staysync.booking.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.staysync.property.OwnedResources;
import com.staysync.shared.outbox.DomainEventPublisher;
import com.staysync.shared.outbox.OutboxEvent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox 소비자 자리의 <b>첫 입주자</b>다.
 *
 * <p>작업지시 03 이 "이벤트 타입에 소비자를 붙이는 지점만 만들고 지금은 로그만 남기는
 * 구현 하나를 둔다"고 했던 그 자리다. P3 의 채널 워커가 옆에 들어온다
 * ({@code DomainEventPublishers} 참조).
 *
 * <p><b>여기서 조직을 정한다.</b> 이벤트에는 {@code propertyId} 가 있고 숙소는 조직에
 * 속한다. 그 매핑을 {@link OwnedResources} 에서 한 번 읽어 그 조직에만 보낸다.
 * 조직을 못 찾으면 <b>아무에게도 보내지 않는다</b> — 모르면 넓게 보내는 쪽으로 기울면
 * 그게 곧 남의 조직 예약이 화면에 뜨는 경로다.
 */
@Component
class CalendarEventBroadcaster implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CalendarEventBroadcaster.class);

    private final CalendarStreamHub hub;
    private final OwnedResources owned;
    private final ObjectMapper objectMapper;

    CalendarEventBroadcaster(CalendarStreamHub hub, OwnedResources owned,
                             ObjectMapper objectMapper) {
        this.hub = hub;
        this.owned = owned;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(OutboxEvent event) {
        Optional<Long> propertyId = propertyIdOf(event);
        if (propertyId.isEmpty()) {
            // 숙소를 알 수 없는 이벤트는 어느 화면과도 관계가 없다. P3 의 채널 이벤트 중
            // 일부가 여기로 올 수 있고, 그건 이 소비자가 다룰 것이 아니다.
            return;
        }

        Optional<Long> orgId = owned.orgIdOfProperty(propertyId.get());
        if (orgId.isEmpty()) {
            // 숙소가 지워졌거나 페이로드가 잘못됐다. 조용히 넘기지 말고 남긴다 —
            // 화면이 갱신되지 않는 증상만으로는 원인을 찾기 어렵다.
            log.warn("이벤트의 숙소를 가진 조직을 찾지 못해 실시간 알림을 보내지 않는다. "
                    + "eventId={} propertyId={}", event.getId(), propertyId.get());
            return;
        }

        hub.broadcast(orgId.get(), event.getEventType(), propertyId.get());
    }

    /**
     * 페이로드에서 숙소 식별자를 읽는다.
     *
     * <p>예약 이벤트({@code ReservationEvents.payloadOf})와 일괄 편집 이벤트가 모두
     * {@code propertyId} 를 담는다. 담지 않는 이벤트가 생기면 그건 캘린더와 무관한
     * 사건이므로 빈 값이 맞다.
     */
    private Optional<Long> propertyIdOf(OutboxEvent event) {
        try {
            JsonNode node = objectMapper.readTree(event.getPayload()).get("propertyId");
            return node == null || node.isNull()
                    ? Optional.empty()
                    : Optional.of(node.asLong());
        } catch (Exception e) {
            log.warn("이벤트 페이로드를 읽지 못했다. eventId={}", event.getId(), e);
            return Optional.empty();
        }
    }
}
