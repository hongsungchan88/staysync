package com.staysync.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 요금 캘린더 셀 하나를 이 값으로 만든다. pricing 이 바깥에서 받는 쓰기 단위다.
 *
 * <p><b>바꾸지 않는 항목도 현재 값을 그대로 담아 넘긴다.</b> "null 이면 유지"로 두면
 * 행이 아직 없는 날에 무엇을 넣을지 pricing 이 정해야 하는데, 비어 있는 날의 기본값은
 * {@code unit.base_price} 이고 그건 property 의 것이다. pricing 이 그걸 알면
 * pricing → property 의존이 생긴다. 조회에서 기본값 채우기를 조립부에 맡긴 것과 같은
 * 이유다({@link DayRate} 참조).
 */
public record RateChange(
        Long ratePlanId,
        LocalDate date,
        BigDecimal price,
        short minStay,
        boolean closedToArrival) {

    public RateChange {
        if (ratePlanId == null || date == null) {
            throw new IllegalArgumentException("요금제와 날짜는 필수입니다.");
        }
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("요금은 0 이상이어야 합니다. price=" + price);
        }
        if (minStay < 1) {
            throw new IllegalArgumentException("최소 숙박일은 1 이상이어야 합니다. minStay=" + minStay);
        }
    }
}
