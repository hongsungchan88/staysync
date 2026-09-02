package com.staysync.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 하루치 요금. pricing 이 바깥에 내보내는 값 타입이다.
 *
 * <p>{@code pricing.domain.RateCalendar} 엔티티를 그대로 넘기지 않는다. 그건 모듈
 * 내부 구현이다.
 *
 * <p>값이 없는 날은 이 목록에 <b>아예 나오지 않는다.</b> 비어 있는 날을 어떤 값으로
 * 채울지는 pricing 이 정할 일이 아니다. 기본값인 {@code unit.base_price} 는 property 의
 * 것이라, pricing 이 그걸 알면 pricing → property 의존이 생긴다. 채우는 책임은
 * 두 모듈을 모두 아는 조립부에 있다.
 */
public record DayRate(Long ratePlanId, LocalDate date, BigDecimal price, short minStay) {
}
