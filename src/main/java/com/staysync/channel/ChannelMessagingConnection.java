package com.staysync.channel;

/**
 * 메시징이 가능한 채널 연결 하나. channel 이 messaging 에 내보내는 값 타입이다.
 *
 * <p>{@code channel.domain.ChannelConnection} 을 그대로 넘기지 않는다. 그건 모듈 내부
 * 구현이고, 무엇보다 자격 증명을 들고 있다 — 그 엔티티가 경계를 넘으면 복호화하는
 * 자리가 {@code ChannelCredentialStore} 하나라는 규칙이 깨진다.
 */
public record ChannelMessagingConnection(Long connectionId, Long propertyId, String channelCode) {
}
