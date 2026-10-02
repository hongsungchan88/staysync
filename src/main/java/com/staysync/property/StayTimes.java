package com.staysync.property;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 숙소의 체크인·체크아웃 시각. property 가 바깥에 내보내는 값 타입이다.
 *
 * <p>{@code property.domain.Property} 를 그대로 넘기지 않는다. 모듈 내부 구현이고,
 * 넘기면 받는 쪽이 숙소의 변경 메서드까지 손에 쥔다. {@code UnitSummary} 와 같은 선이다.
 *
 * <p>{@code zone} 은 숙소 시간대다. 시각을 날짜에 붙일 때 서버 기본 시간대가 아니라 이것을 쓴다 —
 * 배포 JVM 은 UTC 라 기본 시간대로 붙이면 청소 마감이 9시간 늦어진다(작업지시-22).
 */
public record StayTimes(LocalTime checkIn, LocalTime checkOut, ZoneId zone) {
}
