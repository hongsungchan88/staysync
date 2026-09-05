package com.staysync.messaging.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 자주 쓰는 답변. 변수는 이중 중괄호로 감싼 이름이다.
 *
 * <p>치환은 {@code TemplateRenderer} 가 하고, <b>채울 값이 없으면 발송을 막는다.</b>
 * 치환되지 않은 변수가 그대로 나간 메시지는 되돌릴 수 없다.
 */
@Entity
@Table(name = "message_template")
public class MessageTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    /** 조직 안에서 유일하다. 자동 발송 규칙이 이 값으로 템플릿을 가리킨다. */
    @Column(nullable = false, length = 60)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private String body;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MessageTemplate() {
    }

    public MessageTemplate(Long orgId, String code, String name, String body) {
        this.orgId = orgId;
        this.code = code;
        this.name = name;
        this.body = body;
    }

    public Long getId() {
        return id;
    }

    public Long getOrgId() {
        return orgId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getBody() {
        return body;
    }

    public void edit(String name, String body) {
        this.name = name;
        this.body = body;
    }
}
