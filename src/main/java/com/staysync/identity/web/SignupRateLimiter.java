package com.staysync.identity.web;

import com.staysync.shared.web.IpRateLimiter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 가입의 속도 제한. 클라이언트 IP 당 시간 창 안의 요청 수를 센다(작업지시-19 추가분).
 *
 * <p>{@code POST /api/auth/signup} 은 로그인 없이 조직 하나와 계정 하나를 만든다. 가입 화면이
 * 생기면서 스크립트가 무한히 조직을 만드는 길이 열렸다. 사람은 한 번이고, 오타로 다시 해도
 * 두세 번이라 시작값은 1시간 3건({@code staysync.signup.limit}). 세는 방식과 한계는
 * {@link IpRateLimiter} — 창·상한은 공개 HOLD 와 따로 둔다.
 */
@Component
public class SignupRateLimiter extends IpRateLimiter {

    SignupRateLimiter(
            @Value("${staysync.signup.limit.max:3}") int maxPerWindow,
            @Value("${staysync.signup.limit.window:1h}") Duration window) {
        super("가입", maxPerWindow, window);
    }
}
