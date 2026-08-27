package com.staysync.identity.domain;

import com.staysync.shared.security.AuthenticatedUser;
import com.staysync.shared.security.Role;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 로그인 계정.
 *
 * <p>{@code role} 은 별도 테이블이 아니라 {@code user_account.role} VARCHAR 컬럼이고
 * 값은 {@code chk_user_role} CHECK 제약으로 제한된다. 그래서 {@link Role} 은 엔티티가
 * 아니라 열거형이며 {@code @Enumerated(STRING)} 으로 매핑한다.
 *
 * <p>비밀번호는 평문을 들고 있지 않는다. 해시만 저장하며 대조는 인코더가 한다.
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected UserAccount() {
    }

    public UserAccount(Long orgId, String email, String passwordHash, String displayName, Role role) {
        this.orgId = orgId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public Long getOrgId() {
        return orgId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    /** 정지된 계정은 비밀번호가 맞아도 로그인시키지 않는다. */
    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public void recordLogin(OffsetDateTime at) {
        this.lastLoginAt = at;
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    public void suspend() {
        this.status = UserStatus.SUSPENDED;
    }

    /** 토큰에 실을 최소 정보로 줄인다. */
    public AuthenticatedUser toPrincipal() {
        return new AuthenticatedUser(id, orgId, role);
    }
}
