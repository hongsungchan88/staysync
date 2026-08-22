/**
 * 계정, 조직, 역할, 인증 토큰을 다룬다.
 *
 * <p>{@code security} 아래에 토큰 발급·검증과 인증 정책이 있다. 인증 결과를 담는
 * {@code shared.security.AuthenticatedUser} 만 바깥에 드러나며, 다른 모듈은 그것을
 * {@code SecurityContext} 에서 읽을 뿐 이 모듈을 참조하지 않는다. 덕분에 의존은
 * identity → shared 한 방향으로만 흐른다.
 *
 * <p>다른 모듈이 사용자 이름 같은 것을 조회해야 할 때가 오면 그때 최상위 패키지에
 * 공개 인터페이스를 둔다. P5 의 청소 담당자 배정이 첫 사용처가 될 것이다.
 */
package com.staysync.identity;
