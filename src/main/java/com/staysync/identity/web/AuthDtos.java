package com.staysync.identity.web;

import com.staysync.identity.domain.UserAccount;
import com.staysync.shared.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 인증 API 의 요청·응답 본문.
 *
 * <p>레코드가 짧아 파일을 나누지 않고 한곳에 모았다.
 */
final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * 비밀번호 규칙. 가입과 비밀번호 변경이 같은 값을 쓴다 — 둘이 갈리면 가입 때 안 되던 비밀번호로 바꿀 수 있다.
     *
     * <p>길이만 강제한다. 문자 종류 조합을 요구하는 규칙은 실제로는 예측 가능한 비밀번호를 유도한다는 지적이
     * 많아 넣지 않는다.
     */
    static final int PASSWORD_MIN = 10;
    static final int PASSWORD_MAX = 100;

    record SignupRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = PASSWORD_MIN, max = PASSWORD_MAX) String password,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Size(max = 200) String orgName) {
    }

    record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    /** 새 비밀번호 확인 입력은 화면이 맞춰 본다. 서버는 새 값 하나만 받는다. */
    record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = PASSWORD_MIN, max = PASSWORD_MAX) String newPassword) {
    }

    /**
     * 로그인·가입·갱신의 응답.
     *
     * <p>리프레시 토큰은 여기 없다. 쿠키로 나간다(ADR 0006).
     */
    record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {

        static TokenResponse of(String accessToken, long expiresInSeconds) {
            return new TokenResponse(accessToken, "Bearer", expiresInSeconds);
        }
    }

    record MeResponse(Long userId, Long orgId, String email, String displayName, Role role) {

        static MeResponse from(UserAccount user) {
            return new MeResponse(
                    user.getId(), user.getOrgId(), user.getEmail(),
                    user.getDisplayName(), user.getRole());
        }
    }
}
