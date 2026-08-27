package com.staysync.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.staysync.shared.security.AuthenticatedUser;
import com.staysync.shared.security.Role;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * 토큰 발급과 검증. Spring 컨텍스트를 띄우지 않는다.
 *
 * <p>만료 검사는 시계를 기다리는 대신 발급 시각을 과거로 밀어 만든다.
 */
class JwtTokenServiceTest {

    private static final String SECRET = "test-signing-key-with-enough-length-32+";
    private static final String OTHER_SECRET = "completely-different-key-also-long-enough";

    private static final AuthenticatedUser 사용자 =
            new AuthenticatedUser(42L, 7L, Role.MANAGER);

    private final JwtTokenService service = serviceWith(SECRET);

    @Test
    void 발급한_토큰을_검증하면_클레임이_복원된다() {
        String token = service.issueAccessToken(사용자, Instant.now());

        Optional<AuthenticatedUser> 복원 = service.parse(token);

        assertThat(복원).contains(사용자);
    }

    @Test
    void 만료된_토큰은_거부된다() {
        // 액세스 토큰 수명이 30분이므로 31분 전에 발급된 토큰은 이미 만료다.
        Instant 과거 = Instant.now().minus(Duration.ofMinutes(31));
        String token = service.issueAccessToken(사용자, 과거);

        assertThat(service.parse(token)).isEmpty();
    }

    @Test
    void 서명이_다르면_거부된다() {
        String token = serviceWith(OTHER_SECRET).issueAccessToken(사용자, Instant.now());

        assertThat(service.parse(token)).isEmpty();
    }

    @Test
    void 변조된_토큰은_거부된다() {
        String token = service.issueAccessToken(사용자, Instant.now());
        // 페이로드 한 글자를 바꾸면 서명이 맞지 않는다.
        String 변조 = token.substring(0, token.length() - 2)
                + (token.endsWith("A") ? "B" : "A");

        assertThat(service.parse(변조)).isEmpty();
    }

    @Test
    void 토큰은_이메일이나_이름을_담지_않는다() {
        String token = service.issueAccessToken(사용자, Instant.now());

        // 서명만 되어 있고 암호화되어 있지 않으므로 페이로드는 누구나 읽을 수 있다.
        String payload = new String(
                java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                StandardCharsets.UTF_8);

        assertThat(payload).contains("\"org\":7", "\"role\":\"MANAGER\"", "\"sub\":\"42\"");
        assertThat(payload).doesNotContain("@", "email", "name");
    }

    private static JwtTokenService serviceWith(String secret) {
        JwtProperties properties = new JwtProperties(
                secret, "staysync", Duration.ofMinutes(30), Duration.ofDays(14));
        SecretKeySpec key = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        return new JwtTokenService(encoder, decoder, properties);
    }
}
