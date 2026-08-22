package com.staysync.identity.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 토큰 발급 설정. {@code staysync.security.jwt.*} 에 대응한다.
 *
 * <p>수명은 계획서 15.1 이 정한 값(액세스 30분, 리프레시 14일)을 기본으로 둔다.
 *
 * @param secret            HS256 서명 키. 최소 32바이트여야 한다
 * @param issuer            발급자 클레임
 * @param accessTokenTtl    액세스 토큰 수명
 * @param refreshTokenTtl   리프레시 토큰 수명
 */
@ConfigurationProperties(prefix = "staysync.security.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl) {

    /**
     * HS256 이 요구하는 키 길이. 256비트 미만이면 Nimbus 가 서명을 거부한다.
     * 설정 실수를 첫 요청이 아니라 기동 시점에 잡기 위해 여기서 검사한다.
     */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT 서명 키가 설정되지 않았습니다. 환경 변수 JWT_SECRET 을 지정하세요. "
                            + "(.env.example 참고)");
        }
        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT 서명 키는 최소 " + MIN_SECRET_BYTES + "바이트여야 합니다. "
                            + "현재 길이로는 HS256 서명이 불가능합니다.");
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "staysync";
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(30);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(14);
        }
    }
}
