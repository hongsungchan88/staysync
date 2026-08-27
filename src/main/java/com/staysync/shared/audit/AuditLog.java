package com.staysync.shared.audit;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 감사 기록. 계획서 15.1 이 약속한 "주체, 대상, 시각, 변경 전후".
 *
 * <p>도메인 변경과 <b>같은 트랜잭션에서</b> 삽입된다. 변경이 롤백됐는데 "변경했다"는
 * 기록이 남으면 그 로그는 거짓말이 된다. 감사 기록이 거짓말을 하면 없느니만 못하다.
 *
 * <p>{@code beforeValue} 와 {@code afterValue} 에는 <b>바뀐 필드만</b> 담는다.
 * 엔티티를 통째로 직렬화하면 무엇이 바뀌었는지가 오히려 안 보이고, 연락처 같은 값이
 * 딸려 들어가 암호화를 우회하는 경로가 된다(ADR 0007 결과 절).
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 배치가 일으킨 변경에는 주체가 없다. 그래서 nullable 이다. */
    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, length = 20)
    private ActorKind actorKind = ActorKind.USER;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(nullable = false, length = 40)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_value")
    private String beforeValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_value")
    private String afterValue;

    @Column(name = "occurred_at", insertable = false, updatable = false)
    private OffsetDateTime occurredAt;

    protected AuditLog() {
    }

    public AuditLog(Long actorId, ActorKind actorKind, String entityType, Long entityId,
                    String action, String beforeValue, String afterValue) {
        this.actorId = actorId;
        this.actorKind = actorKind;
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
    }

    public Long getId() {
        return id;
    }

    public Long getActorId() {
        return actorId;
    }

    public ActorKind getActorKind() {
        return actorKind;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public String getAction() {
        return action;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }
}
