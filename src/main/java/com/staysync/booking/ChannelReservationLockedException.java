package com.staysync.booking;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 채널에서 들어온 예약을 사람이 여기서 바꾸려 했다(작업지시-26 C·D).
 *
 * <p>날짜를 옮기거나 취소해도 채널에는 나가지 않는다 — 우리 재고만 풀리고, 채널이 다음에 같은 예약을 보내면 원래대로
 * 돌아오며 그사이 팔린 날과 겹친다(검토-02 3절 2·3). 그 채널에서 바꿔야 한다.
 */
public class ChannelReservationLockedException extends DomainException {

    public ChannelReservationLockedException(String message) {
        super("CHANNEL_RESERVATION_LOCKED", message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
