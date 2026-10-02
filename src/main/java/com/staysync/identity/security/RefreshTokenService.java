package com.staysync.identity.security;

import com.staysync.identity.RefreshTokenRepository;
import com.staysync.identity.domain.RefreshFailedException;
import com.staysync.identity.domain.RefreshToken;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리프레시 토큰의 발급, 검증, 회전, 재사용 탐지.
 *
 * <p>설계 근거는 docs/adr/0005-리프레시-토큰-회전.md. 재사용 탐지의 범위(로그인 묶음)와 유예는 그 결과 절이다.
 *
 * <p>토큰 자체는 JWT 가 아니라 난수 문자열이다. 상태가 데이터베이스에 있으므로 토큰은
 * 그 행을 가리키는 열쇠면 충분하고, 클레임을 실을 이유가 없다.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /** 계획서 15.1 이 정한 128비트. */
    private static final int TOKEN_BYTES = 16;

    /**
     * 교체된 토큰을 재사용으로 보지 않는 시간(ADR 0005 결과 절, 2026-10-02).
     *
     * <p>Okta 의 기본값(0~60초 가운데 30초)이다. Auth0 는 기본으로 끄고 초 단위로 켠다. 막으려는 것은 같은
     * 브라우저의 탭 둘이 첫 갱신의 왕복 안에 겹치는 경우(세션 복원·탭 복제)와 응답을 잃은 재시도다 — 앞은
     * 밀리초~초, 뒤는 클라이언트가 실패를 알아채고 다시 보내기까지 수 초~수십 초다. 30초 안의 재사용은 같은
     * 묶음에 갈래를 하나 더 낼 뿐이고, 그 뒤의 재사용은 그대로 유출로 보고 묶음을 끊는다.
     */
    static final Duration REUSE_GRACE = Duration.ofSeconds(30);

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
        // 로그인 한 번이 묶음 하나다. 회전은 이 묶음을 물려받는다.
        return issueInternal(userId, UUID.randomUUID().toString(), now).rawToken();
    }

    /**
     * 제시된 토큰을 검증하고 회전시킨다.
     *
     * <p>정상 경로에서는 새 토큰을 발급하고 기존 토큰에 교체 표시를 한다(조건부 — 이긴 요청 하나만).
     * 교체된 토큰이 다시 오면 <b>교체 뒤 {@link #REUSE_GRACE} 안</b>이면 같은 묶음에 새 토큰을 하나 더 주고,
     * 밖이면 재사용으로 보고 <b>그 묶음만</b> 무효화한다. 같은 계정의 다른 로그인은 살아 있다.
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

        // 무효화가 교체보다 먼저다. 로그아웃이나 묶음 무효화는 교체된 옛 토큰까지 표시하므로, 유예 안의
        // 옛 토큰으로 로그아웃한 세션을 되살릴 수 없다.
        if (existing.isRevoked()) {
            log.warn("갱신 거절: 무효화된 리프레시 토큰. userId={}", existing.getUserId());
            throw new RefreshFailedException();
        }
        if (existing.isExpiredAt(now)) {
            log.warn("갱신 거절: 만료된 리프레시 토큰. userId={} 만료={}",
                    existing.getUserId(), existing.getExpiresAt());
            throw new RefreshFailedException();
        }
        if (existing.isRotated()) {
            return reuseOf(existing, now);
        }

        Issued successor = issueInternal(existing.getUserId(), existing.getFamilyId(), now);
        boolean won = repository.markRotated(existing.getId(), successor.entity().getId(), now) == 1;
        if (!won) {
            // 읽은 뒤 쓰기 전에 다른 요청이 이 토큰을 바꿨다. 둘 중 하나다.
            if (repository.findById(existing.getId()).map(RefreshToken::isRevoked).orElse(true)) {
                // 로그아웃(묶음 무효화)이었다. 예외가 이 트랜잭션을 롤백시켜 방금 만든 토큰도 사라진다.
                log.warn("갱신 거절: 회전 직전에 무효화됐다. userId={}", existing.getUserId());
                throw new RefreshFailedException();
            }
            // 같은 순간의 탭 둘 — 다른 요청이 방금(유예 안) 회전했다. 진 쪽은 유예 안의 재사용과 같으니 방금 만든
            // 토큰을 같은 묶음의 갈래로 둔다. 회전 표시는 이긴 쪽 하나뿐이라 재사용 탐지는 우회되지 않는다
            // (확인-12 5.1 ③).
            log.info("갱신 경합: 같은 토큰을 다른 요청이 먼저 회전했다. 같은 묶음에 갈래로 발급. userId={}",
                    existing.getUserId());
        }
        log.debug("리프레시 토큰 회전. userId={}", existing.getUserId());
        return new Rotation(existing.getUserId(), successor.rawToken());
    }

    /**
     * 교체된 토큰이 다시 왔다. 유예 안이면 같은 묶음에 갈래를 하나 더 낸다(탭 겹침·응답 유실 재시도). 밖이면
     * 유출로 보고 그 묶음만 끊는다.
     */
    private Rotation reuseOf(RefreshToken existing, OffsetDateTime now) {
        if (existing.rotatedWithin(REUSE_GRACE, now)) {
            Issued branch = issueInternal(existing.getUserId(), existing.getFamilyId(), now);
            log.info("교체 직후의 재사용 — 유예 안이라 같은 묶음에 갈래로 발급. userId={}", existing.getUserId());
            return new Rotation(existing.getUserId(), branch.rawToken());
        }
        // 별도 트랜잭션에서 끊는다. 아래에서 던지는 예외가 이 트랜잭션을 롤백시키면 무효화까지 없던 일이 된다.
        int revoked = compromisedTokenHandler.revokeFamily(existing.getFamilyId(), now);
        log.error("갱신 거절: 리프레시 토큰 재사용 탐지(유예 밖). userId={} 그 로그인의 토큰 {}건 무효화",
                existing.getUserId(), revoked);
        throw new RefreshFailedException();
    }

    /**
     * 로그아웃. 그 토큰이 속한 <b>로그인 묶음</b>을 무효화한다.
     *
     * <p>토큰 하나만 끊으면 유예 안의 옛 토큰(같은 묶음의 교체분)으로 30초 안에 세션이 되살아난다. 다른
     * 로그인(다른 기기, 공용 계정의 다른 사람)은 건드리지 않는다.
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
                .ifPresent(token -> repository.revokeFamily(token.getFamilyId(), now));
    }

    private Issued issueInternal(Long userId, String familyId, OffsetDateTime now) {
        String rawToken = randomToken();
        RefreshToken saved = repository.save(new RefreshToken(
                userId, sha256Hex(rawToken), now.plus(properties.refreshTokenTtl()), familyId));
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
