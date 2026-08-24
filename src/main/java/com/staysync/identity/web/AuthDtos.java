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

    record SignupRequest(
            @NotBlank @Email String email,
            // 길이만 강제한다. 문자 종류 조합을 요구하는 규칙은 실제로는 예측 가능한
            // 비밀번호를 유도한다는 지적이 많아 넣지 않는다.
            @NotBlank @Size(min = 10, max = 100) String password,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Size(max = 200) String orgName) {
    }

    record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
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
