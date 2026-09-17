package com.staysync.booking.web;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** 공개 HOLD 요청이 IP 당 상한을 넘었다. 429. 위젯이 이 메시지를 그대로 보여 준다. */
public class HoldRateLimitedException extends DomainException {

    public HoldRateLimitedException() {
        super("HOLD_RATE_LIMITED", "예약 요청이 너무 잦습니다. 잠시 뒤 다시 시도해 주세요.");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }
}
