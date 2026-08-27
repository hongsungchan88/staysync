package com.staysync.identity.domain;

/**
 * 계정 상태.
 *
 * <p>{@code user_account.status} 는 CHECK 제약이 없는 VARCHAR 다. 값의 범위는
 * 이 열거형이 관리한다.
 */
public enum UserStatus {

    ACTIVE,

    /** 로그인을 막되 기록은 남긴다. 예약 이력의 담당자 참조가 끊기면 안 되므로 삭제하지 않는다. */
    SUSPENDED
}
