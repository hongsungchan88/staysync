package com.staysync.property;

import java.math.BigDecimal;

/**
 * 판매 단위의 요약. property 가 바깥에 내보내는 값 타입이다.
 *
 * <p>{@code property.domain.Unit} 엔티티를 그대로 넘기지 않는다. 그건 모듈 내부
 * 구현이라 다른 모듈이 참조하면 {@code ModularityTest} 가 깨진다.
 *
 * <p>{@code defaultRatePlanId} 를 함께 담는 이유가 있다. {@code rate_calendar} 는
 * {@code unit_id} 가 아니라 {@code rate_plan_id} 로 키가 잡혀 있어, 요금을 읽으려면
 * 판매 단위마다 요금제 식별자를 알아야 한다. 그 매핑은 {@code rate_plan} 테이블을
 * 가진 property 의 몫이다. 이걸 넘기지 않으면 pricing 이나 조립부가 남의 테이블을
 * 뒤지게 된다.
 *
 * @param defaultRatePlanId 기본 요금제. 판매 단위 등록 시 자동 생성되므로 보통 존재한다
 */
public record UnitSummary(
        Long id,
        Long propertyId,
        String name,
        short totalUnits,
        BigDecimal basePrice,
        Long defaultRatePlanId) {
}
