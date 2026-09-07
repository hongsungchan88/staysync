package com.staysync.messaging.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 메시지 한 통.
 *
 * <p><b>{@code externalId} 가 수신 멱등성의 키다.</b> 같은 메시지가 두 번 와도 한
 * 건이어야 한다 — 실제 채널의 웹훅은 최소 1회 전달이고 시뮬레이터도 일부러 중복해
 * 보낸다. {@code uq_message_external} 이 최종 방어선이다(V6).
 *
 * <p>우리가 보낸 메시지에는 {@code externalId} 가 없다. 채널이 매기는 값이라서다.
 * 발송이 두 번 나가지 않게 하는 것은 {@code sync_job} 의 멱등성 키가 맡는다.
 *
 * <p>{@code ai_generated} 와 {@code ai_confidence} 는 매핑하지 않았다. P5 의 AI 초안이
 * 쓰는 값이고, 읽는 곳이 없는 컬럼을 미리 올리면 그 자리를 두고 헷갈린다.
 */
@Entity
@Table(name = "message")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "thread_id", nullable = false)
    private Long threadId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageSender sender;

    @Column(nullable = false)
    private String body;

    /** 채널 측 메시지 식별자. 우리가 보낸 것에는 없다. */
    @Column(name = "external_id", length = 120)
    private String externalId;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt = OffsetDateTime.now();

    protected Message() {
    }

    private Message(Long threadId, MessageDirection direction, MessageSender sender,
                    String body, String externalId, OffsetDateTime sentAt) {
        this.threadId = threadId;
        this.direction = direction;
        this.sender = sender;
        this.body = body;
        this.externalId = externalId;
        if (sentAt != null) {
            this.sentAt = sentAt;
        }
    }

    /** 게스트가 보낸 것. */
    public static Message inbound(Long threadId, String externalId, String body,
                                  OffsetDateTime sentAt) {
        return new Message(threadId, MessageDirection.INBOUND, MessageSender.GUEST,
                body, externalId, sentAt);
    }

    /** 우리가 보낸 것. {@code sender} 로 사람과 자동 발송을 가른다. */
    public static Message outbound(Long threadId, MessageSender sender, String body) {
        return new Message(threadId, MessageDirection.OUTBOUND, sender, body, null, null);
    }

    /**
     * 우리끼리 보는 알림. <b>채널로 나가지 않는다.</b> P4 15주차에 더했다.
     *
     * <p>방향이 {@code INBOUND} 인 것이 요점이다. {@code OUTBOUND} 로 두면 게스트에게
     * 실제로 나간 자동 발송({@code OUTBOUND} + {@code SYSTEM})과 구분되지 않고, 그러면
     * 화면이 "보냈다"고 거짓말을 한다. 발송 작업을 만드는 경로는 {@code OUTBOUND} 만
     * 지나므로 이 메시지는 어디로도 나가지 않는다.
     */
    public static Message systemNote(Long threadId, String body) {
        return new Message(threadId, MessageDirection.INBOUND, MessageSender.SYSTEM,
                body, null, null);
    }

    public Long getId() {
        return id;
    }

    public Long getThreadId() {
        return threadId;
    }

    public MessageDirection getDirection() {
        return direction;
    }

    public MessageSender getSender() {
        return sender;
    }

    public String getBody() {
        return body;
    }

    public String getExternalId() {
        return externalId;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }
}
