package com.staysync.booking;

/**
 * 체크아웃이 남긴 것을 되돌리기 전에 거두는 자리. <b>구현은 ops 에 있다</b>(청소 태스크).
 *
 * <p>의존 방향은 그대로 ops → booking 이다. booking 이 ops 를 부르면 순환이 되므로 booking 은
 * 이 인터페이스만 알고, ops 가 구현해 빈으로 올린다. 체크아웃은 Outbox 로 태스크를 만들지만
 * 되돌리기는 <b>같은 트랜잭션에서</b> 거둔다 — "청소가 이미 시작됐으면 막는다"는 판정이
 * 되돌리기보다 먼저 나야 하고, 되돌리기가 롤백되면 거둔 태스크도 되살아나야 한다.
 */
public interface CheckOutFollowUps {

    /**
     * 그 예약의 체크아웃이 남긴 것을 거둔다. 거둘 수 없으면(이미 손댄 청소) 도메인 예외를 던진다.
     * 호출자의 트랜잭션에 합류해야 한다.
     */
    void withdraw(Long reservationId);
}
