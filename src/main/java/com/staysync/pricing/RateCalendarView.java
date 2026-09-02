package com.staysync.pricing;

import java.time.LocalDate;
import java.util.List;

/**
 * pricing 이 바깥에 공개하는 읽기 전용 API.
 *
 * <p>{@code property.UnitCatalog} 와 같은 형식이다. 캘린더 조립부는 이 인터페이스만
 * 참조하고 {@code pricing.domain} 을 보지 않는다.
 *
 * <p>요금 편집은 9주차다. 지금은 읽기만 연다.
 */
public interface RateCalendarView {

    /**
     * 요금제들의 기간 요금을 한 번에 읽는다.
     *
     * <p>요금이 설정되지 않은 날은 결과에 없다. 호출하는 쪽이 기본값으로 채운다.
     */
    List<DayRate> ratesOf(List<Long> ratePlanIds, LocalDate from, LocalDate to);
}
