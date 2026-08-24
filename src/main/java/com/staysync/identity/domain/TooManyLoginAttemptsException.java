package com.staysync.identity.domain;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 로그인 시도 제한에 걸렸다.
 *
 * <p>존재하지 않는 이메일에도 똑같이 적용된다. 존재하는 계정에만 잠금이 걸리면 429
 * 응답이 계정 존재 여부를 알려 주는 신호가 되어, {@link LoginFailedException} 으로
 * 응답을 통일한 의미가 사라진다.
 */
public class TooManyLoginAttemptsException extends DomainException {

    public TooManyLoginAttemptsException() {
        super("TOO_MANY_LOGIN_ATTEMPTS", "로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }
}
