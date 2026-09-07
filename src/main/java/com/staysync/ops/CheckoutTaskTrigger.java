package com.staysync.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.staysync.shared.outbox.DomainEventPublisher;
import com.staysync.shared.outbox.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox 소비자 자리의 <b>네 번째 입주자</b>다.
 *
 * <p>첫 입주자는 P2 9주차의 캘린더 실시간 갱신, 둘째는 P3 12주차의 채널 전파,
 * 셋째는 P4 14주차의 자동 발송이다. 여기는 체크아웃을 듣고 청소 태스크를 만든다
 * (계획서 8.6).
 *
 * <pre>
 *   RESERVATION_CHECKED_OUT → 방이 비었다 → CLEANING 태스크를 만든다
 * </pre>
 *
 * <p><b>이벤트에서 예약 식별자만 꺼낸다.</b> 나머지는 지금 값을 다시 읽는다. 페이로드의
 * 상태를 그대로 믿으면 이미 취소된 예약에 청소 태스크가 생긴다. 채널 전파와 자동
 * 발송이 같은 이유로 같은 방식을 쓴다.
 *
 * <p><b>소비자를 새로 붙일 때는 Outbox 백로그가 비어 있어야 한다.</b> 아래의 중복
 * 방지는 같은 이벤트가 두 번 오는 것을 막지, <b>옛 이벤트가 처음 오는 것은 막지
 * 못한다.</b> 밀린 이벤트가 남은 채로 이 소비자를 붙였다면 그 한 번의 배수에서 과거
 * 체크아웃 전부에 태스크가 생긴다. 확인-05 7절이 비운 것을 확인한 기록이다.
 *
 * <p>예외를 삼키지 않는다. {@code OutboxRelay} 가 실패로 기록하고 다음 주기에 다시
 * 준다 — 조용히 성공으로 처리하면 청소 태스크가 영영 생기지 않는다.
 */
@Component
class CheckoutTaskTrigger implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CheckoutTaskTrigger.class);

    /** {@code ReservationEvents.CHECKED_OUT} 과 같은 값이다. */
    private static final String RESERVATION_CHECKED_OUT = "RESERVATION_CHECKED_OUT";

    private final OpsTaskService tasks;
    private final ObjectMapper json;

    CheckoutTaskTrigger(OpsTaskService tasks, ObjectMapper json) {
        this.tasks = tasks;
        this.json = json;
    }

    @Override
    public void publish(OutboxEvent event) {
        if (!RESERVATION_CHECKED_OUT.equals(event.getEventType())) {
            // 나머지는 이 소비자와 관계없는 사건이다. 조용히 넘긴다.
            return;
        }
        Long reservationId = reservationIdOf(event);
        if (reservationId == null) {
            log.warn("체크아웃 이벤트에 예약 식별자가 없어 청소 태스크를 만들지 않는다. eventId={}",
                    event.getId());
            return;
        }
        tasks.createCleaningFor(reservationId);
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
