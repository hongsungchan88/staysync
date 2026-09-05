package com.staysync.messaging.domain;

/**
 * 자동 발송의 트리거. 계획서 8.5 의 넷이고 {@code chk_rule_trigger} 와 짝이다.
 *
 * <p>{@link #RESERVATION_CONFIRMED} 만 사건 기반이고 나머지 셋은 날짜 기반이다.
 * 그 차이가 경로를 가른다 — 앞은 Outbox 소비자가, 뒤는 하루 한 번 도는 스케줄러가 맡는다.
 */
public enum MessageTrigger {

    /** 예약이 확정됐다. Outbox 의 {@code RESERVATION_CONFIRMED} 를 소비한다. */
    RESERVATION_CONFIRMED(0),

    /** 체크인 하루 전. */
    BEFORE_CHECK_IN(-1),

    /** 체크아웃 당일. */
    ON_CHECK_OUT(0),

    /** 체크아웃 다음 날. 후기 요청이 여기 붙는다. */
    AFTER_CHECK_OUT(1);

    private final int dayOffset;

    MessageTrigger(int dayOffset) {
        this.dayOffset = dayOffset;
    }

    /**
     * 기준일에서 며칠 떨어져 있는지.
     *
     * <p>스케줄러가 "오늘 보낼 것"을 찾을 때 쓴다. 체크인 하루 전 규칙은 오늘 기준
     * <b>내일</b> 체크인하는 예약이 대상이므로 부호가 뒤집힌다.
     */
    public int dayOffset() {
        return dayOffset;
    }

    /** 체크아웃일을 기준으로 삼는 트리거인지. 아니면 체크인일이다. */
    public boolean basedOnCheckOut() {
        return this == ON_CHECK_OUT || this == AFTER_CHECK_OUT;
    }
}
