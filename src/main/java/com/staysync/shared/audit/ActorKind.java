package com.staysync.shared.audit;

/**
 * 변경을 일으킨 주체의 종류.
 *
 * <p>{@code audit_log.actor_kind} 컬럼에 대응한다. CHECK 제약은 없고 값 범위는 이
 * 열거형이 관리한다.
 */
public enum ActorKind {

    /** 로그인한 사용자의 요청. {@code actorId} 가 채워진다. */
    USER,

    /**
     * 배치나 스케줄러. {@code actorId} 는 비어 있다.
     *
     * <p>배치에는 {@code SecurityContext} 가 없다. {@code audit_log.actor_id} 가
     * nullable 인 이유가 이것이다.
     */
    SYSTEM,

    /** 채널에서 들어온 변경. P3 의 예약 수신이 쓴다. */
    CHANNEL,

    /** AI 가 적용한 변경. P5 의 요금 추천 자동 적용이 쓴다. */
    AI
}
