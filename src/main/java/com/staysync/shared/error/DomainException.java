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

    /**
     * 응답 {@code details} 에 실을 항목. 기본은 없다. 사람이 다음 행동을 고르는 데 필요한
     * 목록(예: 수량을 못 줄이게 막는 날짜들)이 있는 예외만 재정의한다.
     */
    public java.util.List<String> details() {
        return java.util.List.of();
    }
}
