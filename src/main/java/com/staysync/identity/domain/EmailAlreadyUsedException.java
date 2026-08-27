package com.staysync.identity.domain;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 이미 쓰이는 이메일로 가입을 시도했다.
 *
 * <p>가입에서는 이메일 존재 여부를 숨기지 않는다. 숨기면 사용자가 왜 가입이 안 되는지
 * 알 수 없다. 로그인 실패를 뭉뚱그리는 것과는 목적이 다르다.
 */
public class EmailAlreadyUsedException extends DomainException {

    public EmailAlreadyUsedException(String email) {
        super("EMAIL_ALREADY_USED", "이미 사용 중인 이메일입니다. email=" + email);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
