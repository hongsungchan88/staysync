package com.staysync.shared.outbox;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 발행 대기 중인 도메인 이벤트.
 *
 * <p>도메인 변경과 <b>같은 트랜잭션에서</b> 삽입된다. 그것이 이 패턴의 전부다.
 * 트랜잭션 안에서 외부를 호출하면 실패해도 롤백되지 않고, 트랜잭션 밖에서 호출하면
 * 이벤트가 유실된다. 둘 다 피하려고 이벤트를 데이터베이스에 먼저 적는다(계획서 4.4).
 *
 * <p>{@code payload} 에는 <b>식별자만</b> 담는다. 이름도 연락처도 넣지 않는다.
 * 4주차에 게스트 연락처를 암호화해 놓고 이벤트 페이로드에 평문으로 실으면 암호화를
 * 우회하는 두 번째 경로가 생긴다. 근거는 ADR 0007 결과 절.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** 컬럼이 {@code SMALLINT} 라 {@code short} 여야 한다. {@code int} 면 검증이 막는다. */
    @Column(name = "retry_count", nullable = false)
    private short retryCount = 0;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
    }

    public OutboxEvent(String aggregateType, Long aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public short getRetryCount() {
        return retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public void markPublished(OffsetDateTime at) {
        this.publishedAt = at;
        this.lastError = null;
    }

    /**
     * 발행 실패를 기록한다.
     *
     * <p>{@code publishedAt} 은 그대로 비워 둔다. 다음 주기에 다시 시도되어야 하기
     * 때문이다.
     *
     * @return 이 실패로 재시도 상한에 <b>처음 닿았으면</b> true. 경고를 한 번만 남기기 위한 것이다
     */
    public boolean recordFailure(String error, short retryLimit) {
        boolean wasBelowLimit = retryCount < retryLimit;
        if (retryCount < Short.MAX_VALUE) {
            retryCount++;
        }
        // 원인을 사람이 보고 다시 넣을 수 있어야 하므로 사유를 남긴다.
        this.lastError = truncate(error);
        return wasBelowLimit && retryCount >= retryLimit;
    }

    /** {@code last_error} 는 TEXT 지만 스택 전체를 넣으면 읽기 어렵다. */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000) + "...(생략)";
    }
}
