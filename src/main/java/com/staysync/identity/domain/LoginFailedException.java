package com.staysync.identity.domain;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 로그인 거절.
 *
 * <p>사유를 구분하지 않는다. 없는 이메일, 틀린 비밀번호, 정지된 계정이 모두 같은 응답을
 * 받는다. 구분하면 응답 자체가 "그 이메일은 가입되어 있다"는 신호가 된다.
 *
 * <p>사유는 서버 로그에만 남긴다.
 */
public class LoginFailedException extends DomainException {

    public LoginFailedException() {
        super("LOGIN_FAILED", "이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNAUTHORIZED;
    }
}
