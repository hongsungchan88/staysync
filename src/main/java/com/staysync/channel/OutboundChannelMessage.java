package com.staysync.channel;

/**
 * 채널로 내보낼 메시지.
 *
 * <p><b>보낸 뒤에는 되돌릴 수 없다.</b> 이 타입이 만들어지는 자리마다 그 전제가 선다 —
 * 치환되지 않은 변수, 취소된 예약, 중복 발송이 전부 여기 도달하기 전에 걸러져야 한다.
 *
 * <p>{@link InboundChannelMessage} 와 같은 이유로 모듈 최상위에 둔다.
 *
 * @param externalThreadId 채널 측 대화 식별자. 어느 대화에 답하는지다
 * @param channelBookingId 예약의 채널 측 번호. 대화가 아직 없는 채널이 이걸로 연다
 */
public record OutboundChannelMessage(
        String externalThreadId,
        String channelBookingId,
        String body) {

    public OutboundChannelMessage {
        if (body == null || body.isBlank()) {
            // 빈 메시지가 게스트에게 나가면 되돌릴 수 없다.
            throw new IllegalArgumentException("메시지 본문은 비어 있을 수 없습니다.");
        }
    }
}
