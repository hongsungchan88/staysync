package com.staysync.identity;

import com.staysync.identity.domain.*;
import com.staysync.identity.security.JwtTokenService;
import com.staysync.identity.security.LoginAttemptLimiter;
import com.staysync.identity.security.RefreshTokenService;
import com.staysync.shared.security.AuthenticatedUser;
import com.staysync.shared.security.Role;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입, 로그인, 갱신, 로그아웃의 흐름을 엮는다.
 *
 * <p>토큰을 어떻게 실어 보낼지(본문이냐 쿠키냐)는 이 클래스가 모른다. 그건 HTTP 표현의
 * 문제라 컨트롤러가 정한다. 여기서는 어떤 토큰이 나가야 하는지만 결정한다.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final OrganizationRepository organizationRepo;
    private final UserAccountRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptLimiter loginAttemptLimiter;

    public AuthService(OrganizationRepository organizationRepo,
                       UserAccountRepository userRepo,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService,
                       RefreshTokenService refreshTokenService,
                       LoginAttemptLimiter loginAttemptLimiter) {
        this.organizationRepo = organizationRepo;
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    /**
     * 가입. 조직 하나와 OWNER 한 명이 함께 생긴다.
     *
     * <p>개인 호스트가 대상이라 조직을 따로 만드는 절차를 두지 않는다. 매니저나 청소
     * 담당자를 초대하는 기능은 P4 다.
     */
    @Transactional
    public TokenPair signup(String email, String rawPassword, String displayName, String orgName,
                            OffsetDateTime now) {
        String normalizedEmail = normalize(email);
        // uq_user_email 이 최종 방어선이지만, 제약 위반 예외를 409 로 옮기는 것보다
        // 먼저 확인하는 편이 메시지가 분명하다. 경합은 유니크 제약이 잡는다.
        if (userRepo.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyUsedException(normalizedEmail);
        }

        Organization organization = organizationRepo.save(new Organization(orgName));
        UserAccount user = userRepo.save(new UserAccount(
                organization.getId(),
                normalizedEmail,
                passwordEncoder.encode(rawPassword),
                displayName,
                Role.OWNER));

        log.info("가입 완료. userId={} orgId={}", user.getId(), organization.getId());
        return issueFor(user, now);
    }

    /**
     * 로그인.
     *
     * <p>실패 사유를 응답에서 구분하지 않는다. 없는 이메일과 틀린 비밀번호, 정지된 계정이
     * 모두 같은 예외로 나간다. 구분하면 계정 존재 여부가 새어 나간다.
     */
    @Transactional
    public TokenPair login(String email, String rawPassword, OffsetDateTime now) {
        String normalizedEmail = normalize(email);
        Instant instant = now.toInstant();

        if (loginAttemptLimiter.isLocked(normalizedEmail, instant)) {
            log.warn("로그인 거절: 시도 제한. email={}", normalizedEmail);
            throw new TooManyLoginAttemptsException();
        }

        UserAccount user = userRepo.findByEmail(normalizedEmail).orElse(null);
        if (user == null) {
            // 존재하지 않는 이메일도 실패로 센다. 그러지 않으면 429 가 나오는지 여부만으로
            // 가입 여부를 알 수 있다.
            loginAttemptLimiter.recordFailure(normalizedEmail, instant);
            log.warn("로그인 실패: 존재하지 않는 이메일. email={}", normalizedEmail);
            throw new LoginFailedException();
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            loginAttemptLimiter.recordFailure(normalizedEmail, instant);
            log.warn("로그인 실패: 비밀번호 불일치. userId={}", user.getId());
            throw new LoginFailedException();
        }
        if (!user.isActive()) {
            loginAttemptLimiter.recordFailure(normalizedEmail, instant);
            log.warn("로그인 실패: 활성 상태가 아닌 계정. userId={}", user.getId());
            throw new LoginFailedException();
        }

        loginAttemptLimiter.recordSuccess(normalizedEmail);
        user.recordLogin(now);
        return issueFor(user, now);
    }

    /**
     * 갱신. 리프레시 토큰을 회전시키고 새 쌍을 만든다.
     *
     * <p>인증 없이 호출된다. 리프레시 토큰 자체가 자격증명이다.
     */
    @Transactional
    public TokenPair refresh(String rawRefreshToken, OffsetDateTime now) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(rawRefreshToken, now);

        UserAccount user = userRepo.findById(rotation.userId())
                .orElseThrow(() -> {
                    // 토큰은 유효한데 계정이 사라진 경우. 정상 흐름에서는 나오지 않는다.
                    log.warn("갱신 거절: 토큰이 가리키는 계정이 없다. userId={}", rotation.userId());
                    return new RefreshFailedException();
                });
        if (!user.isActive()) {
            log.warn("갱신 거절: 활성 상태가 아닌 계정. userId={}", user.getId());
            throw new RefreshFailedException();
        }

        return new TokenPair(
                jwtTokenService.issueAccessToken(user.toPrincipal(), now.toInstant()),
                rotation.rawToken());
    }

    /** 로그아웃. 멱등하다. 쿠키가 없거나 이미 무효화된 토큰이어도 조용히 끝난다. */
    @Transactional
    public void logout(String rawRefreshToken, OffsetDateTime now) {
        refreshTokenService.revoke(rawRefreshToken, now);
    }

    @Transactional(readOnly = true)
    public UserAccount requireUser(Long userId) {
        return userRepo.findById(userId)
                .orElseThrow(() -> new RefreshFailedException());
    }

    private TokenPair issueFor(UserAccount user, OffsetDateTime now) {
        AuthenticatedUser principal = user.toPrincipal();
        return new TokenPair(
                jwtTokenService.issueAccessToken(principal, now.toInstant()),
                refreshTokenService.issue(user.getId(), now));
    }

    /** 이메일은 소문자로 맞춰 저장하고 조회한다. 대소문자만 다른 중복 계정을 막는다. */
    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /** 발급된 토큰 한 쌍. 실어 보내는 방법은 컨트롤러가 정한다. */
    public record TokenPair(String accessToken, String refreshToken) {
    }
}
