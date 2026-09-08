package com.staysync.identity.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.staysync.shared.security.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 인증 정책.
 *
 * <p>shared 가 아니라 identity 에 둔다. 토큰을 어떻게 검증하고 주체를 어떻게 만드는지는
 * identity 의 책임이며, shared 에 두면 shared → identity 의 역방향 의존이 생긴다.
 * 다른 모듈은 이 클래스를 참조하지 않고 {@link AuthenticatedUser} 만 읽는다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 계획서 15.1 이 정한 BCrypt cost.
     *
     * <p>기본값 10 이 아니라 12 다. 해시 한 번에 드는 시간이 네 배가 되어 대입 공격의
     * 비용을 올린다. 로그인은 드물게 일어나므로 이 지연은 사용자에게 문제가 되지 않는다.
     */
    private static final int BCRYPT_COST = 12;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }

    /**
     * HS256 대칭키. 발급자와 검증자가 같은 프로세스라 키를 나눌 이유가 없다.
     * 자세한 선택 근거는 docs/adr/0004-jwt-인증.md.
     */
    private static SecretKeySpec signingKey(JwtProperties properties) {
        return new SecretKeySpec(
                properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        return NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * 검증된 토큰을 {@link AuthenticatedUser} 를 주체로 갖는 인증 객체로 바꾼다.
     *
     * <p>기본 {@code JwtAuthenticationToken} 은 주체가 {@code Jwt} 라, 컨트롤러마다
     * 클레임을 꺼내 쓰게 된다. 그러면 토큰 형식이 모듈 전체로 새어 나간다. 여기서 한 번
     * 변환해 두면 바깥은 클레임 이름을 알 필요가 없다.
     */
    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            AuthenticatedUser user = JwtTokenService.toPrincipal(jwt);
            var authorities = List.of(new SimpleGrantedAuthority(user.role().authority()));
            return new UsernamePasswordAuthenticationToken(user, jwt, authorities);
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, Converter<Jwt, AbstractAuthenticationToken> converter) throws Exception {

        return http
                // 토큰 기반 API 다. 세션 쿠키를 쓰지 않으므로 CSRF 공격 경로가 없다.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 가입, 로그인, 갱신은 토큰 없이 들어와야 하므로 열어 둔다.
                        // 갱신은 쿠키의 리프레시 토큰 자체가 자격증명이다.
                        .requestMatchers("/api/auth/signup", "/api/auth/login", "/api/auth/refresh")
                        .permitAll()
                        // 로그아웃과 /me 는 인증이 필요하다. anyRequest 에 걸린다.
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // iCal 발행. 계획서 6.2 대로 URL 자체가 인증이다 — 에어비앤비
                        // 서버가 읽어 가므로 헤더도 쿠키도 실을 수 없다. 그래서 토큰이
                        // 추측 불가능한 난수여야 하고, 없는 토큰은 404 로 답해 존재를
                        // 알리지 않는다. 읽기 전용이라 GET 만 연다.
                        .requestMatchers(HttpMethod.GET, "/public/ical/*.ics").permitAll()
                        // 직접예약 위젯. 계획서 8.7 이고 로그인 없이 열린다(P4 16주차).
                        //
                        // **쓰기가 열린 유일한 익명 경로다.** 그래서 서버가 숙소 식별자
                        // 외에는 아무것도 믿지 않는다 — 가용도 요금도 금액도 다시 구하고,
                        // 화면이 보낸 금액은 대조용으로만 쓴다. 조직 스코핑을 걸 수 없는
                        // 자리이므로 그 몫을 값 검증이 대신한다.
                        .requestMatchers(HttpMethod.GET, "/public/booking/*/availability").permitAll()
                        .requestMatchers(HttpMethod.POST, "/public/booking/*/hold").permitAll()
                        // 직접예약 결제(P4 16주차). 둘 다 우리 세션을 가질 수 없다 —
                        // 하나는 위젯 손님이, 하나는 포트원이 부른다.
                        //
                        // 인증 대신 지키는 것이 각각 있다. prepare 는 **확인 코드**가
                        // 지키고(추측 불가능한 난수, iCal 발행 토큰과 같은 성질),
                        // webhook 은 **서명**이 지킨다. 서명이 맞지 않으면 본문을
                        // 읽지도 않는다 — 계획서 14.2 의 검증 시나리오 10.
                        .requestMatchers(HttpMethod.POST, "/public/payments/prepare").permitAll()
                        .requestMatchers(HttpMethod.POST, "/public/payments/portone/webhook")
                        .permitAll()
                        // 확정 여부 확인. 확인 코드를 아는 사람만 물어볼 수 있고
                        // 응답에는 상태 문자열 하나뿐이다.
                        .requestMatchers(HttpMethod.GET, "/public/payments/status/*").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }
}
