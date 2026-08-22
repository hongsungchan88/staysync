package com.staysync.shared.security;

/**
 * 사용자 역할.
 *
 * <p>identity 가 아니라 shared 에 두었다. 인가 판단은 property, booking 등 거의 모든
 * 모듈의 웹 계층에서 일어나는데, 이 열거형이 {@code identity.domain} 에 있으면 그 모든
 * 모듈이 identity 를 참조하게 된다. 역할은 인증 결과를 읽는 쪽의 공통 어휘다.
 *
 * <p>{@code user_account.role} 컬럼의 {@code chk_user_role} CHECK 제약과 값이 일치해야
 * 한다. 별도 테이블이 아니라 문자열 컬럼이므로 여기에 상수를 추가할 때는 V1__init.sql 의
 * 제약도 함께 고쳐야 한다.
 */
public enum Role {

    /** 조직의 소유자. 사용자 관리를 포함한 모든 권한을 갖는다. */
    OWNER,

    /** 운영 담당자. 숙소와 예약, 요금을 다루지만 사용자 관리는 할 수 없다. */
    MANAGER,

    /** 청소 담당자. 배정된 태스크와 그에 필요한 범위만 본다. */
    HOUSEKEEPER;

    /** Spring Security 가 기대하는 권한 문자열. */
    public String authority() {
        return "ROLE_" + name();
    }
}
