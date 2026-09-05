package com.staysync.messaging.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 자동 발송 규칙. 계획서 8.5 의 트리거 넷이다.
 *
 * <p><b>경로가 둘로 갈린다.</b> 예약 확정은 Outbox 이벤트를 소비하고, 나머지 셋은
 * 시간 기반 스케줄러가 돈다. 트리거의 성격이 다르기 때문이다 — 앞은 사건이 나면
 * 곧바로여야 하고, 뒤는 "그날이 되면"이다.
 *
 * <p>{@code propertyId} 가 비어 있으면 조직의 모든 숙소에 적용한다. 숙소가 하나뿐인
 * 개인 호스트가 대부분이라 그때마다 숙소를 고르게 하지 않는다.
 */
@Entity
@Table(name = "message_rule")
public class MessageRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    /** 비어 있으면 조직 전체. */
    @Column(name = "property_id")
    private Long propertyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30)
    private MessageTrigger triggerType;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    /**
     * 꺼 두면 나가지 않는다.
     *
     * <p>지우는 대신 끄게 한 이유는 발송 이력 때문이다. 규칙을 지우면
     * {@code message_dispatch} 가 함께 지워지고, 다시 만들었을 때 이미 보낸 예약에
     * 한 번 더 나간다.
     */
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MessageRule() {
    }

    public MessageRule(Long orgId, Long propertyId, MessageTrigger triggerType, Long templateId) {
        this.orgId = orgId;
        this.propertyId = propertyId;
        this.triggerType = triggerType;
        this.templateId = templateId;
    }

    public Long getId() {
        return id;
    }

    public Long getOrgId() {
        return orgId;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public MessageTrigger getTriggerType() {
        return triggerType;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void changeEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 이 규칙이 그 숙소에 적용되는지. 숙소를 지정하지 않은 규칙은 전부에 적용된다. */
    public boolean appliesTo(Long propertyId) {
        return this.propertyId == null || this.propertyId.equals(propertyId);
    }
}
