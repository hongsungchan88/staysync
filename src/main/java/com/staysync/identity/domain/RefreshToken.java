package com.staysync.identity.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 리프레시 토큰의 서버측 상태.
 *
 * <p>토큰 원문은 저장하지 않는다. {@code tokenHash} 는 SHA-256 을 소문자 16진수로
 * 적은 값이며, 갱신 요청이 오면 들어온 토큰을 같은 방식으로 해시해 대조한다.
 * 데이터베이스가 통째로 유출돼도 저장된 값을 그대로 제시할 수 없다.
 *
 * <p>{@code replacedBy} 가 회전 체인을 잇는다. 값이 있으면 이미 교체된 토큰이다. 교체된 토큰이
 * 다시 들어오면 <b>교체 뒤 유예 안이면</b> 정상(탭 겹침·응답 유실 재시도)으로, 밖이면 유출로 본다.
 *
 * <p>{@code familyId} 는 한 번의 로그인에서 이어진 토큰들의 묶음이다. 유출로 보면 그 묶음만 끊는다 —
 * 같은 계정의 다른 로그인(공용 계정의 다른 사람)은 살린다(V11, ADR 0005 결과 절).
 *
 * <p>근거는 docs/adr/0005-리프레시-토큰-회전.md.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", insertable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** 이 토큰을 대체한 토큰의 식별자. NULL 이면 아직 회전되지 않았다. */
    @Column(name = "replaced_by")
    private Long replacedBy;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "family_id", nullable = false, length = 40)
    private String familyId;

    /** 교체된 시각. 유예는 여기서부터 센다. V11 이전에 교체된 토큰은 비어 있고 유예 밖으로 본다. */
    @Column(name = "rotated_at")
    private OffsetDateTime rotatedAt;

    protected RefreshToken() {
    }

    public RefreshToken(Long userId, String tokenHash, OffsetDateTime expiresAt, String familyId) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.familyId = familyId;
    }

    public String getFamilyId() {
        return familyId;
    }

    /** 교체된 지 {@code grace} 안인지. 교체되지 않았거나 교체 시각을 모르면 아니다. */
    public boolean rotatedWithin(java.time.Duration grace, OffsetDateTime now) {
        return rotatedAt != null && !now.isAfter(rotatedAt.plus(grace));
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getReplacedBy() {
        return replacedBy;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    /** 이미 다른 토큰으로 교체됐는지. 이 상태의 토큰이 제시되면 재사용이다. */
    public boolean isRotated() {
        return replacedBy != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(OffsetDateTime now) {
        return !expiresAt.isAfter(now);
    }

    /** 갱신에 쓸 수 있는 상태인지. 회전, 무효화, 만료 중 하나라도 걸리면 못 쓴다. */
    public boolean isUsableAt(OffsetDateTime now) {
        return !isRotated() && !isRevoked() && !isExpiredAt(now);
    }

    public void revoke(OffsetDateTime at) {
        if (this.revokedAt == null) {
            this.revokedAt = at;
        }
    }
}
