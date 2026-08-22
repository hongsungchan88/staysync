/**
 * 모든 모듈이 함께 쓰는 공통 기반. 도메인 지식은 담지 않는다.
 *
 * <p>{@code error}(예외 체계와 응답 규약), {@code lock}(재고 잠금 추상화),
 * {@code config}(실행 환경 설정)로 나뉜다.
 *
 * <p>이 모듈만 {@link org.springframework.modulith.ApplicationModule.Type#OPEN} 이다.
 * Spring Modulith 는 기본적으로 모듈 최상위 패키지의 타입만 공개로 보는데, shared 는
 * 최상위에 타입을 두지 않고 성격별 하위 패키지로 나눠 놓았다. 그대로 두면 다른 모듈이
 * {@code shared.error.DomainException} 을 상속하거나 {@code shared.lock.UnitLock} 을
 * 주입받는 것이 전부 경계 위반으로 잡힌다. 하위 패키지 구분은 shared 안에서 갈래를
 * 나누기 위한 것이지 감추기 위한 것이 아니므로 모듈 전체를 연다.
 *
 * <p>여는 것은 shared 뿐이다. 다른 모듈은 여전히 최상위 패키지에 공개한 타입만
 * 바깥에서 쓸 수 있다.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.staysync.shared;
