package com.staysync.shared.time;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 숙소가 정해지지 않는 자리(전역 스케줄·여러 숙소에 걸친 필터)의 기준 시간대. Asia/Seoul 이다.
 *
 * <p><b>서버 기본 시간대에 기대지 않는다 — 배포 JVM 은 UTC 다.</b> 인자 없는 {@code LocalDate.now()} 나
 * {@code ZoneId.systemDefault()} 는 배포 환경에서 KST 00:00~09:00 사이에 어제를 오늘로 본다(작업지시-22).
 * 숙소가 정해지는 자리는 이것 대신 숙소 시간대({@code StayTimes.zone()})를 쓴다.
 */
public final class ServiceZone {

    /** {@code @Scheduled(zone = ...)} 에 쓰려고 문자열 상수로도 둔다. */
    public static final String ID = "Asia/Seoul";

    public static final ZoneId SEOUL = ZoneId.of(ID);

    private ServiceZone() {
    }

    public static LocalDate today() {
        return LocalDate.now(SEOUL);
    }
}
