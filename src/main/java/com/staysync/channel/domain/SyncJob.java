package com.staysync.channel.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 채널로 보낼(또는 채널에서 가져올) 작업 한 건.
 *
 * <p>병합 버퍼가 flush 하면 이 행이 하나 생기고, {@code SyncJobWorker} 가 집어 간다.
 * 재시도 정책은 ADR 0012 에 있다.
 *
 * <p><b>상태 변경은 워커 안에서만 한다.</b> 클레임과 완료가 한곳에 있어야
 * {@code FOR UPDATE SKIP LOCKED} 가 의미를 갖는다. 바깥에서 상태를 바꾸면 워커가
 * 집어 든 행이 그 아래에서 바뀐다.
 */
@Entity
@Table(name = "sync_job")
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 30)
    private SyncJobType jobType;

    /** 어댑터에 넘길 명령. {@code AriUpdateCommand} 를 직렬화해 담는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    /**
     * 같은 변경이 두 번 실리지 않게 하는 키. {@code hash(connId, dateRange, values)} 다.
     *
     * <p>Outbox 가 최소 1회 전달이라 같은 이벤트가 두 번 올 수 있다. 버퍼 윈도 안이면
     * 합쳐지지만 윈도를 넘겨 오면 작업이 둘 생긴다. 그걸 막는 것이 이 키다.
     */
    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SyncJobStatus status = SyncJobStatus.PENDING;

    @Column(nullable = false)
    private short attempt = 0;

    @Column(name = "next_run_at", nullable = false)
    private OffsetDateTime nextRunAt = OffsetDateTime.now();

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected SyncJob() {
    }

    public SyncJob(Long connectionId, SyncJobType jobType, String payload, String idempotencyKey) {
        this.connectionId = connectionId;
        this.jobType = jobType;
        this.payload = payload;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() {
        return id;
    }

    public Long getConnectionId() {
        return connectionId;
    }

    public SyncJobType getJobType() {
        return jobType;
    }

    public String getPayload() {
        return payload;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public SyncJobStatus getStatus() {
        return status;
    }

    public short getAttempt() {
        return attempt;
    }

    public OffsetDateTime getNextRunAt() {
        return nextRunAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void succeed() {
        this.status = SyncJobStatus.SUCCESS;
        this.lastError = null;
    }

    /**
     * 나중에 다시 시도한다. 상태를 {@link SyncJobStatus#PENDING} 으로 되돌린다.
     *
     * <p>{@code RUNNING} 인 채로 두면 다음 클레임에서 빠져 영영 돌지 않는다.
     */
    public void retryAfter(Duration delay, String reason) {
        this.status = SyncJobStatus.PENDING;
        this.nextRunAt = OffsetDateTime.now().plus(delay);
        this.lastError = reason;
    }

    /** 더 시도하지 않는다. <b>행은 남는다.</b> */
    public void markDead(String reason) {
        this.status = SyncJobStatus.DEAD;
        this.lastError = reason;
    }

    /** 워커가 집었다는 표시. 네이티브 클레임 쿼리가 이미 올려 둔 값을 엔티티에도 맞춘다. */
    public void markRunning(short attempt) {
        this.status = SyncJobStatus.RUNNING;
        this.attempt = attempt;
    }
}
