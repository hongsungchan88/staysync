package com.mockota;

import java.time.Instant;

/**
 * 채널이 주고받는 메시지. <b>우리 도메인 타입이 아니다.</b>
 *
 * <p>{@code MockBooking} 과 같은 이유로 {@code com.staysync} 의 어떤 타입도 참조하지
 * 않고 JSON 은 {@code snake_case} 로 나간다. 이름이 우연히 맞아떨어져 통과하면
 * 어댑터의 매핑이 실제로 도는지 알 수 없다.
 *
 * @param messageId 채널 측 메시지 식별자. 멱등성 키다. <b>중복될 수 있다</b> —
 *                  같은 식별자로 두 번 보내는 것이 시나리오 하나다
 * @param threadId  채널 측 대화 식별자. 보통 예약 하나에 하나다
 * @param bookingId 이 대화가 붙은 예약. 우리 쪽이 스레드를 예약에 잇는 데 쓴다
 * @param sender    {@code GUEST} 또는 {@code HOST}
 * @param sentAt    채널이 기록한 시각. <b>순서가 뒤집혀 나갈 수 있다</b>
 */
public record MockMessage(
        String messageId,
        String threadId,
        String bookingId,
        String sender,
        String body,
        Instant sentAt) {

    public static final String GUEST = "GUEST";
    public static final String HOST = "HOST";
}
