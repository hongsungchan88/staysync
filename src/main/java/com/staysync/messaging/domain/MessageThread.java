package com.staysync.messaging.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 채널 하나에서 오가는 대화 하나. 보통 예약 하나에 붙는다.
 *
 * <p><b>스레드의 신원은 {@code (channel_code, external_id)} 다</b>(V1 의
 * {@code uq_thread_external}). 예약 수신이 {@code (channel_code, channel_booking_id)} 로
 * 멱등성을 얻는 것과 같은 자리다. 같은 대화가 두 번 열리면 인박스에 같은 게스트와의
 * 대화가 둘로 갈라지고, 호스트는 한쪽만 보고 답한다.
 *
 * <p>{@code reservationId} 는 비어 있을 수 있다. 채널이 예약보다 메시지를 먼저 보낼 수
 * 있고, 그때 대화를 버리면 게스트의 문의가 사라진다. 예약이 들어온 뒤에 이어 붙인다.
 *
 * <p><b>게스트 연락처를 담지 않는다.</b> {@code guestId} 로 가리키기만 한다. 여기
 * 평문을 두면 암호화를 우회하는 두 번째 경로가 된다(ADR 0007).
 */
@Entity
@Table(name = "message_thread")
public class MessageThread {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "guest_id")
    private Long guestId;

    @Column(name = "channel_code", nullable = false, length = 40)
    private String channelCode;

    /** 채널 측 대화 식별자. 채널 코드와 묶여 이 스레드의 신원이다. */
    @Column(name = "external_id", length = 120)
    private String externalId;

    @Column(length = 200)
    private String subject;

    /**
     * 아직 읽지 않은 게스트 메시지 수.
     *
     * <p>메시지마다 읽음 여부를 두지 않았다. 인박스가 필요로 하는 것은 "이 대화에 새
     * 것이 있는가" 하나이고, 메시지 단위 읽음은 화면이 쓰지 않는 값을 매번 갱신하게 한다.
     */
    @Column(name = "unread_count", nullable = false)
    private short unreadCount = 0;

    @Column(name = "last_message_at")
    private OffsetDateTime lastMessageAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MessageThread() {
    }

    public MessageThread(Long propertyId, String channelCode, String externalId,
                         Long reservationId, Long guestId, String subject) {
        this.propertyId = propertyId;
        this.channelCode = channelCode;
        this.externalId = externalId;
        this.reservationId = reservationId;
        this.guestId = guestId;
        this.subject = subject;
    }

    public Long getId() {
        return id;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public Long getGuestId() {
        return guestId;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getSubject() {
        return subject;
    }

    public short getUnreadCount() {
        return unreadCount;
    }

    public OffsetDateTime getLastMessageAt() {
        return lastMessageAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 게스트 메시지가 도착했다.
     *
     * <p>{@code lastMessageAt} 은 <b>뒤로 가지 않는다.</b> 채널이 순서를 뒤집어 보내는
     * 것이 정상이라(11주차 결정), 늦게 도착한 옛 메시지가 목록의 정렬을 되돌리면
     * 새 대화가 아래로 밀린다.
     */
    public void receiveGuestMessage(OffsetDateTime sentAt) {
        this.unreadCount = (short) Math.min(this.unreadCount + 1, Short.MAX_VALUE);
        touchLastMessage(sentAt);
    }

    /** 우리가 보냈다. 미읽음은 늘지 않는다. */
    public void recordOutbound(OffsetDateTime sentAt) {
        touchLastMessage(sentAt);
    }

    /**
     * 우리끼리 보는 알림이 붙었다. P4 15주차의 청소 태스크 알림이 쓴다.
     *
     * <p><b>미읽음을 늘린다.</b> 담당자가 볼 곳이 생기는 것이 이 알림의 목적인데
     * 목록에서 표시가 나지 않으면 아무도 보지 않는다.
     */
    public void receiveSystemNote(OffsetDateTime sentAt) {
        this.unreadCount = (short) Math.min(this.unreadCount + 1, Short.MAX_VALUE);
        touchLastMessage(sentAt);
    }

    private void touchLastMessage(OffsetDateTime sentAt) {
        if (sentAt != null && (lastMessageAt == null || sentAt.isAfter(lastMessageAt))) {
            this.lastMessageAt = sentAt;
        }
    }

    /**
     * 채널 측 대화 식별자를 뒤늦게 채운다.
     *
     * <p>자동 발송이 게스트보다 먼저 말을 걸면 그때는 채널의 대화 식별자를 모른다.
     * 나중에 게스트 메시지가 오면 그 스레드를 찾아 여기서 채운다 — 새로 만들면
     * <b>같은 게스트와의 대화가 둘로 갈라지고 호스트는 한쪽만 보고 답한다.</b>
     *
     * <p>이미 채워져 있으면 바꾸지 않는다. 덮어쓰면 다른 대화를 이 스레드로 끌어온다.
     */
    public void adoptExternalId(String externalId) {
        if (this.externalId == null && externalId != null) {
            this.externalId = externalId;
        }
    }

    /** 호스트가 대화를 열어 봤다. */
    public void markRead() {
        this.unreadCount = 0;
    }

    /**
     * 예약을 뒤늦게 잇는다. 이미 이어져 있으면 바꾸지 않는다.
     *
     * <p>덮어쓰면 같은 대화가 다른 예약으로 옮겨 간다. 채널이 예약번호를 잘못 실어
     * 보내는 경우가 있고, 그때 대화가 남의 예약에 붙으면 화면이 조용히 거짓말을 한다.
     */
    public void attachReservation(Long reservationId, Long guestId) {
        if (this.reservationId == null) {
            this.reservationId = reservationId;
        }
        if (this.guestId == null) {
            this.guestId = guestId;
        }
    }
}
