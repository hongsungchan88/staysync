package com.staysync.identity.security;

import com.staysync.shared.security.AuthenticatedUser;
import com.staysync.shared.security.Role;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

/**
 * 액세스 토큰을 발급하고 해석한다.
 *
 * <p>서명과 검증은 Spring Security 가 감싼 Nimbus 에 맡긴다. 직접 작성한 암호 코드는
 * 없다. 근거는 docs/adr/0004-jwt-인증.md.
 *
 * <p>리프레시 토큰은 여기서 다루지 않는다. 그쪽은 JWT 가 아니라 난수 문자열이고
 * 상태를 데이터베이스에 두기 때문이다. {@code RefreshTokenService} 가 담당한다.
 */
@Service
public class JwtTokenService {

    /** 조직 식별자를 담는 사설 클레임. */
    static final String CLAIM_ORG = "org";

    /** 역할을 담는 사설 클레임. */
    static final String CLAIM_ROLE = "role";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final JwtProperties properties;

    public JwtTokenService(JwtEncoder encoder, JwtDecoder decoder, JwtProperties properties) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.properties = properties;
    }

    /**
     * 주체를 담은 액세스 토큰을 발급한다.
     *
     * <p>토큰에 넣는 것은 식별자와 역할뿐이다. 이름이나 이메일은 넣지 않는다. 토큰은
     * 서명되어 있을 뿐 암호화되어 있지 않아 누구나 내용을 읽을 수 있기 때문이다.
     */
    public String issueAccessToken(AuthenticatedUser user, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(properties.accessTokenTtl()))
                .subject(String.valueOf(user.userId()))
                .claim(CLAIM_ORG, user.orgId())
                .claim(CLAIM_ROLE, user.role().name())
                .build();
        // 헤더에 HS256 을 명시해야 한다. 지정하지 않으면 Nimbus 가 RS256 을 가정하고
        // 대칭키에서 서명 키를 고르지 못해 "Failed to select a JWK signing key" 로 끝난다.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * 토큰을 검증하고 주체를 복원한다.
     *
     * <p>서명이 다르거나 만료됐으면 비어 있는 값을 돌려준다. 호출하는 쪽이 실패 사유를
     * 구분할 필요가 없어 예외 대신 {@link Optional} 을 쓴다.
     */
    public Optional<AuthenticatedUser> parse(String token) {
        try {
            return Optional.of(toPrincipal(decoder.decode(token)));
        } catch (JwtException e) {
            return Optional.empty();
        }
    }

    /** 검증이 끝난 토큰에서 주체를 꺼낸다. 리소스 서버 필터가 쓴다. */
    static AuthenticatedUser toPrincipal(Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        Long orgId = jwt.getClaim(CLAIM_ORG) instanceof Number n
                ? n.longValue()
                : Long.valueOf(String.valueOf(jwt.getClaim(CLAIM_ORG)));
        Role role = Role.valueOf(jwt.getClaimAsString(CLAIM_ROLE));
        return new AuthenticatedUser(userId, orgId, role);
    }
}
