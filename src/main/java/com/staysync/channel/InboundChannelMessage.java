package com.staysync.channel;

import java.time.OffsetDateTime;

/**
 * 채널에서 들어온 메시지를 표준화한 형태.
 *
 * <p><b>{@code channel.port} 가 아니라 모듈 최상위에 둔다.</b> messaging 모듈이 이
 * 타입을 받아야 하고, Spring Modulith 규칙상 다른 모듈이 참조할 수 있는 것은 모듈
 * 최상위 패키지의 타입뿐이다. {@code channel.port.InboundBooking} 은 channel 안에서만
 * 오가므로 그쪽에 남아 있어도 되지만, 이건 경계를 넘는다.
 *
 * <p>같은 값을 담은 타입을 안팎에 두 벌 만들지 않았다. 두 벌이면 어느 쪽이 진짜인지가
 * 흐려지고, 필드가 하나 늘 때마다 두 곳을 고쳐야 한다.
 *
 * @param externalMessageId 채널 측 메시지 식별자. <b>멱등성 키다</b> — 같은 메시지가
 *                          두 번 와도 한 건이어야 한다
 * @param externalThreadId  채널 측 대화 식별자. 스레드의 신원이다
 * @param channelBookingId  이 대화가 붙은 예약의 채널 측 번호. 스레드를 예약에 잇는다
 * @param sentAt            채널이 기록한 시각. <b>순서가 뒤집혀 올 수 있다</b>
 */
public record InboundChannelMessage(
        String externalMessageId,
        String externalThreadId,
        String channelBookingId,
        String body,
        OffsetDateTime sentAt) {

    public InboundChannelMessage {
        if (externalMessageId == null || externalMessageId.isBlank()) {
            throw new IllegalArgumentException("채널 메시지 식별자는 필수입니다. 멱등성 키로 쓰입니다.");
        }
        if (externalThreadId == null || externalThreadId.isBlank()) {
            throw new IllegalArgumentException("채널 대화 식별자는 필수입니다. 스레드의 신원입니다.");
        }
        if (body == null) {
            body = "";
        }
        if (sentAt == null) {
            sentAt = OffsetDateTime.now();
        }
    }
}
