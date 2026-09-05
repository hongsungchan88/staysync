package com.staysync.messaging.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 자동 발송이 실제로 나갔다는 기록. <b>같은 규칙이 같은 예약에 두 번 나가지 않게 한다.</b>
 *
 * <p>이 엔티티의 존재 이유가 {@code uq_dispatch_once} 하나다. 애플리케이션에서
 * "이미 보냈나" 를 확인하고 넣으면 그 사이에 두 번째가 끼어든다. Outbox 는 최소 1회
 * 전달이라 같은 예약 확정 이벤트가 두 번 오고, 시간 기반 스케줄러는 재기동하면 같은
 * 날짜를 다시 훑는다. <b>데이터베이스가 막게 한다.</b>
 *
 * <p>행을 지우지 않는다. 지우면 그 예약에 같은 규칙이 다시 나가고, 게스트에게 같은
 * 안내가 두 번 간다. 되돌릴 수 없다.
 */
@Entity
@Table(name = "message_dispatch")
public class MessageDispatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "dispatched_at", insertable = false, updatable = false)
    private OffsetDateTime dispatchedAt;

    protected MessageDispatch() {
    }

    public MessageDispatch(Long ruleId, Long reservationId, Long messageId) {
        this.ruleId = ruleId;
        this.reservationId = reservationId;
        this.messageId = messageId;
    }

    public Long getId() {
        return id;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public OffsetDateTime getDispatchedAt() {
        return dispatchedAt;
    }
}
