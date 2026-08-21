package com.staysync.shared.error;

import org.springframework.http.HttpStatus;

/** 도메인 규칙 위반을 나타내는 예외의 최상위 타입. */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** HTTP 응답 상태. 하위 예외가 필요에 따라 재정의한다. */
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
