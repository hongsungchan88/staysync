package com.staysync.identity.security;

import com.staysync.identity.RefreshTokenRepository;
import com.staysync.identity.domain.RefreshFailedException;
import com.staysync.identity.domain.RefreshToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리프레시 토큰의 발급, 검증, 회전, 재사용 탐지.
 *
 * <p>설계 근거는 docs/adr/0005-리프레시-토큰-회전.md.
 *
 * <p>토큰 자체는 JWT 가 아니라 난수 문자열이다. 상태가 데이터베이스에 있으므로 토큰은
 * 그 행을 가리키는 열쇠면 충분하고, 클레임을 실을 이유가 없다.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 계획서 15.1 이 정한 128비트. */
    private static final int TOKEN_BYTES = 16;

    private final RefreshTokenRepository repository;
    private final CompromisedTokenHandler compromisedTokenHandler;
    private final JwtProperties properties;
    private final SecureRandom random = new SecureRandom();

    RefreshTokenService(RefreshTokenRepository repository,
                        CompromisedTokenHandler compromisedTokenHandler,
                        JwtProperties properties) {
        this.repository = repository;
        this.compromisedTokenHandler = compromisedTokenHandler;
        this.properties = properties;
    }

    /**
     * 새 토큰을 발급한다.
     *
     * @return 저장하지 않은 원문. 이 값을 놓치면 다시 만들 수 없다
     */
    @Transactional
    public String issue(Long userId, OffsetDateTime now) {
        return issueInternal(userId, now).rawToken();
    }

    /**
     * 제시된 토큰을 검증하고 회전시킨다.
     *
     * <p>정상 경로에서는 새 토큰을 발급하고 기존 토큰을 체인의 이전 고리로 만든다.
     * 재사용이 탐지되면 그 사용자의 살아 있는 토큰을 전부 무효화한다.
     *
     * @throws RefreshFailedException 사유를 구분하지 않는다. 구분은 로그에만 남는다
     */
    @Transactional
    public Rotation rotate(String rawToken, OffsetDateTime now) {
        if (rawToken == null || rawToken.isBlank()) {
            log.warn("갱신 거절: 리프레시 토큰이 제시되지 않았다");
            throw new RefreshFailedException();
        }

        RefreshToken existing = repository.findByTokenHash(sha256Hex(rawToken))
                .orElseThrow(() -> {
                    // ADR 0005 가 정리에 7일 유예를 둔 이유가 이 구분이다. 만료된 행이
                    // 남아 있으면 아래의 "만료" 로그로 갈리고, 여기로 오는 것은 발급된 적
                    // 없거나 유예를 넘겨 지워진 토큰이다. 앞은 공격 신호일 수 있다.
                    log.warn("갱신 거절: 존재하지 않는 리프레시 토큰이 제시됐다");
                    return new RefreshFailedException();
                });

        if (existing.isRotated()) {
            // 정상 사용자는 이미 교체한 토큰을 다시 쓸 이유가 없다. 유출로 본다.
            // 별도 트랜잭션에서 지운다. 아래에서 던지는 예외가 이 트랜잭션을 롤백시키면
            // 무효화까지 없던 일이 되기 때문이다. CompromisedTokenHandler 주석 참고.
            int revoked = compromisedTokenHandler.revokeAllOf(existing.getUserId(), now);
            log.error("갱신 거절: 리프레시 토큰 재사용 탐지. userId={} 무효화한 토큰={}건",
                    existing.getUserId(), revoked);
            throw new RefreshFailedException();
        }
        if (existing.isRevoked()) {
            log.warn("갱신 거절: 무효화된 리프레시 토큰. userId={}", existing.getUserId());
            throw new RefreshFailedException();
        }
        if (existing.isExpiredAt(now)) {
            log.warn("갱신 거절: 만료된 리프레시 토큰. userId={} 만료={}",
                    existing.getUserId(), existing.getExpiresAt());
            throw new RefreshFailedException();
        }

        Issued successor = issueInternal(existing.getUserId(), now);
        existing.rotateTo(successor.entity().getId());
        log.debug("리프레시 토큰 회전. userId={}", existing.getUserId());

        return new Rotation(existing.getUserId(), successor.rawToken());
    }

    /**
     * 토큰 하나를 무효화한다. 로그아웃에서 쓴다.
     *
     * <p>멱등하다. 쿠키가 없거나 이미 무효화된 토큰이어도 조용히 넘어간다. 로그아웃은
     * 실패할 이유가 없는 요청이다.
     */
    @Transactional
    public void revoke(String rawToken, OffsetDateTime now) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(sha256Hex(rawToken))
                .ifPresent(token -> token.revoke(now));
    }

    private Issued issueInternal(Long userId, OffsetDateTime now) {
        String rawToken = randomToken();
        RefreshToken saved = repository.save(new RefreshToken(
                userId, sha256Hex(rawToken), now.plus(properties.refreshTokenTtl())));
        // 회전에서 replaced_by 에 넣을 식별자가 필요하다. 영속화가 끝나야 id 가 생긴다.
        repository.flush();
        return new Issued(rawToken, saved);
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        // URL 안전 인코딩. 쿠키 값에 그대로 들어간다.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 저장과 조회에 쓰는 해시.
     *
     * <p>BCrypt 가 아니라 SHA-256 인 이유는 두 가지다. 토큰이 128비트 난수라 사전 공격
     * 대상이 아니고, 솔트가 없어야 해시값으로 바로 조회할 수 있다. 솔트가 붙으면 사용자의
     * 토큰을 전부 꺼내 하나씩 대조해야 한다.
     */
    static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없는 런타임입니다.", e);
        }
    }

    /** 회전 결과. 새 원문과 그것이 속한 사용자. */
    public record Rotation(Long userId, String rawToken) {
    }

    private record Issued(String rawToken, RefreshToken entity) {
    }
}
