package com.staysync.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰을 담는 쿠키를 만들고 읽는다.
 *
 * <p>액세스 토큰은 응답 본문으로, 리프레시 토큰은 이 쿠키로 나간다. 두 토큰의 위험도가
 * 다르기 때문이다. 근거는 docs/adr/0006-토큰-전달-방식.md.
 *
 * <p>{@code Path} 는 {@code /api/auth} 다. 갱신만 생각하면 {@code /api/auth/refresh} 로
 * 더 좁힐 수 있지만 로그아웃도 이 쿠키를 읽어야 한다. ADR 0006 에 적어 둔 선택이다.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "refresh_token";

    /** 갱신과 로그아웃 두 경로만 포함하는 가장 좁은 접두어. */
    static final String PATH = "/api/auth";

    private final boolean secure;
    private final Duration maxAge;

    public RefreshCookie(
            @Value("${staysync.security.cookie.secure:true}") boolean secure,
            JwtProperties jwtProperties) {
        this.secure = secure;
        // 쿠키 수명은 리프레시 토큰 수명과 같아야 한다. 쿠키가 먼저 사라지면 아직 살아
        // 있는 토큰을 쓸 수 없고, 나중까지 남으면 만료된 토큰을 계속 보내게 된다.
        this.maxAge = jwtProperties.refreshTokenTtl();
    }

    /** 토큰을 담은 쿠키. */
    public ResponseCookie issue(String rawToken) {
        return base(rawToken).maxAge(maxAge).build();
    }

    /**
     * 쿠키를 지우는 쿠키.
     *
     * <p>같은 이름·경로에 빈 값과 수명 0 을 실어 덮어쓴다. 속성이 하나라도 다르면
     * 브라우저가 다른 쿠키로 보고 기존 것을 남겨 둔다.
     */
    public ResponseCookie expire() {
        return base("").maxAge(0).build();
    }

    public Optional<String> readFrom(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                // 자바스크립트가 읽을 이유가 없다. XSS 로도 꺼낼 수 없게 한다.
                .httpOnly(true)
                // local 은 HTTPS 가 아니라 끈다. 켜 두면 브라우저가 쿠키를 저장하지 않는다.
                .secure(secure)
                // 다른 출처에서 시작된 요청에는 실리지 않는다. CSRF 경로를 막는다.
                .sameSite("Strict")
                .path(PATH);
    }
}
