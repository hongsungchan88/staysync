package com.staysync.property;

import java.time.LocalTime;

/**
 * 숙소의 체크인·체크아웃 시각. property 가 바깥에 내보내는 값 타입이다.
 *
 * <p>{@code property.domain.Property} 를 그대로 넘기지 않는다. 모듈 내부 구현이고,
 * 넘기면 받는 쪽이 숙소의 변경 메서드까지 손에 쥔다. {@code UnitSummary} 와 같은 선이다.
 */
public record StayTimes(LocalTime checkIn, LocalTime checkOut) {
}
