package com.staysync.identity.web;

import com.staysync.identity.AuthService;
import com.staysync.identity.security.JwtProperties;
import com.staysync.identity.security.RefreshCookie;
import com.staysync.identity.web.AuthDtos.*;
import com.staysync.shared.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 인증 엔드포인트.
 *
 * <p>토큰을 어떻게 실어 보낼지를 정하는 곳이다. 액세스 토큰은 응답 본문에, 리프레시
 * 토큰은 {@code Set-Cookie} 에 담는다. 근거는 docs/adr/0006-토큰-전달-방식.md.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService authService;
    private final RefreshCookie refreshCookie;
    private final long accessTokenSeconds;

    AuthController(AuthService authService, RefreshCookie refreshCookie, JwtProperties jwtProperties) {
        this.authService = authService;
        this.refreshCookie = refreshCookie;
        this.accessTokenSeconds = jwtProperties.accessTokenTtl().toSeconds();
    }

    @PostMapping("/signup")
    ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
        var tokens = authService.signup(
                request.email(), request.password(), request.displayName(), request.orgName(),
                OffsetDateTime.now());
        return withRefreshCookie(HttpStatus.CREATED, tokens.accessToken(), tokens.refreshToken());
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        var tokens = authService.login(request.email(), request.password(), OffsetDateTime.now());
        return withRefreshCookie(HttpStatus.OK, tokens.accessToken(), tokens.refreshToken());
    }

    /**
     * 갱신. 인증 없이 호출된다. 쿠키의 리프레시 토큰이 자격증명이다.
     *
     * <p>쿠키가 아예 없는 경우도 서비스에 그대로 넘긴다. 여기서 따로 분기하면 "쿠키
     * 없음"과 "토큰이 유효하지 않음"이 다른 응답으로 갈려 정보가 새어 나간다.
     */
    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(HttpServletRequest request) {
        String presented = refreshCookie.readFrom(request).orElse(null);
        var tokens = authService.refresh(presented, OffsetDateTime.now());
        return withRefreshCookie(HttpStatus.OK, tokens.accessToken(), tokens.refreshToken());
    }

    /** 로그아웃. 멱등하다. 쿠키가 없어도 200 이다. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        refreshCookie.readFrom(request)
                .ifPresent(token -> authService.logout(token, OffsetDateTime.now()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.expire().toString())
                .build();
    }

    @GetMapping("/me")
    MeResponse me() {
        AuthenticatedUser principal = AuthenticatedUser.current()
                .orElseThrow(() -> new IllegalStateException(
                        "인증이 필요한 경로인데 주체가 없다. SecurityConfig 설정을 확인할 것."));
        return MeResponse.from(authService.requireUser(principal.userId()));
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(
            HttpStatus status, String accessToken, String refreshToken) {
        ResponseCookie cookie = refreshCookie.issue(refreshToken);
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(TokenResponse.of(accessToken, accessTokenSeconds));
    }
}
