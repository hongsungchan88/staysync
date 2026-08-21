package com.staysync.channel.port;

/**
 * 어댑터가 실제로 지원하는 기능.
 *
 * <p>런타임에 기능을 확인해 동작을 바꾼다. 예를 들어 iCal 어댑터는
 * {@link #PUSH_RATE} 를 지원하지 않으므로, 사용자가 요금을 바꿔도 해당 채널에는
 * 전파 작업을 만들지 않고 화면에 지원하지 않는다는 표시만 남긴다.
 *
 * <p>나중에 에어비앤비 공식 API 어댑터를 추가하면 기존 코드를 고치지 않고
 * 기능이 열린다.
 */
public enum Capability {
    PUSH_AVAILABILITY,
    PUSH_RATE,
    PUSH_RESTRICTION,
    PULL_BOOKING,
    WEBHOOK_BOOKING,
    MESSAGING,
    REVIEW,
    CONTENT
}
