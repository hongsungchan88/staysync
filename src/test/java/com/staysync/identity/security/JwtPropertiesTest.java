package com.staysync.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 서명 키 설정이 잘못됐을 때 기동 시점에 걸리는지 확인한다.
 *
 * <p>키가 비었거나 짧은 것은 배포 실수로 흔히 나오는데, 이걸 첫 로그인 요청에서
 * 발견하면 이미 서비스가 떠 있는 상태다. 기동을 막는 편이 낫다.
 */
class JwtPropertiesTest {

    @Test
    void 서명_키가_없으면_기동에_실패한다() {
        assertThatThrownBy(() -> properties(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void 서명_키가_32바이트_미만이면_기동에_실패한다() {
        assertThatThrownBy(() -> properties("너무-짧은-키"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    void 수명을_지정하지_않으면_계획서_기본값을_쓴다() {
        JwtProperties 기본값 = new JwtProperties(
                "test-signing-key-with-enough-length-32+", null, null, null);

        assertThat(기본값.accessTokenTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(기본값.refreshTokenTtl()).isEqualTo(Duration.ofDays(14));
        assertThat(기본값.issuer()).isEqualTo("staysync");
    }

    private static JwtProperties properties(String secret) {
        return new JwtProperties(secret, "staysync", Duration.ofMinutes(30), Duration.ofDays(14));
    }
}
