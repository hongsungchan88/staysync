package com.staysync.booking.web;

import com.staysync.shared.web.IpRateLimiter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 공개 HOLD 생성의 속도 제한. 클라이언트 IP 당 시간 창 안의 요청 수를 센다(작업지시-18 D).
 *
 * <p>{@code POST /public/booking/{id}/hold} 는 로그인 없이 재고를 15분씩 잡는다. 반복하면
 * 업체의 실제 판매 날짜를 계속 비워 둘 수 있다 — 결제까지 가지 않아도 된다. 세는 방식과
 * 한계는 {@link IpRateLimiter}. 창·상한은 {@code staysync.public-booking.hold-limit}.
 */
@Component
public class PublicHoldRateLimiter extends IpRateLimiter {

    PublicHoldRateLimiter(
            @Value("${staysync.public-booking.hold-limit.max:5}") int maxPerWindow,
            @Value("${staysync.public-booking.hold-limit.window:10m}") Duration window) {
        super("공개 HOLD", maxPerWindow, window);
    }
}
