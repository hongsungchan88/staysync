package com.staysync.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.staysync.messaging.domain.MessageTrigger;
import com.staysync.shared.outbox.DomainEventPublisher;
import com.staysync.shared.outbox.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox 소비자 자리의 <b>세 번째 입주자</b>다.
 *
 * <p>첫 입주자는 P2 9주차의 캘린더 실시간 갱신, 둘째는 P3 12주차의 채널 전파다.
 * 여기는 같은 사건을 듣고 게스트에게 안내를 보낸다.
 *
 * <pre>
 *   RESERVATION_CONFIRMED → 예약이 확정됐다 → 확정 안내 규칙을 적용한다
 * </pre>
 *
 * <p><b>이벤트에서 값을 읽지 않고 예약 식별자만 꺼낸다.</b> 나머지는 지금 값을 다시
 * 읽는다. 페이로드의 값을 그대로 쓰면 오래된 이벤트가 나중에 처리될 때 이미 취소된
 * 예약에 "예약이 확정되었습니다" 가 나간다. 채널 전파가 같은 이유로 같은 방식을 쓴다.
 *
 * <p><b>Outbox 는 최소 1회 전달이다.</b> 같은 이벤트가 두 번 와도 게스트에게는 한 번만
 * 가야 하고, 그걸 막는 것은 {@code uq_dispatch_once} 다.
 *
 * <p>예외를 삼키지 않는다. {@code OutboxRelay} 가 실패로 기록하고 다음 주기에 다시
 * 준다 — 조용히 성공으로 처리하면 안내가 영영 나가지 않는다.
 */
@Component
class ReservationMessageTrigger implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReservationMessageTrigger.class);

    /** {@code ReservationEvents.CONFIRMED} 와 같은 값이다. */
    private static final String RESERVATION_CONFIRMED = "RESERVATION_CONFIRMED";

    private final AutoMessageService auto;
    private final ObjectMapper json;

    ReservationMessageTrigger(AutoMessageService auto, ObjectMapper json) {
        this.auto = auto;
        this.json = json;
    }

    @Override
    public void publish(OutboxEvent event) {
        if (!RESERVATION_CONFIRMED.equals(event.getEventType())) {
            // 나머지는 이 소비자와 관계없는 사건이다. 조용히 넘긴다.
            return;
        }
        Long reservationId = reservationIdOf(event);
        if (reservationId == null) {
            log.warn("예약 확정 이벤트에 예약 식별자가 없어 자동 발송하지 않는다. eventId={}",
                    event.getId());
            return;
        }
        auto.apply(MessageTrigger.RESERVATION_CONFIRMED, reservationId);
    }

    private Long reservationIdOf(OutboxEvent event) {
        try {
            JsonNode payload = json.readTree(event.getPayload());
            return payload.hasNonNull("reservationId")
                    ? payload.get("reservationId").asLong() : event.getAggregateId();
        } catch (Exception e) {
            // 페이로드가 깨졌어도 집계 식별자는 있다. 그걸로 충분하다.
            return event.getAggregateId();
        }
    }
}
