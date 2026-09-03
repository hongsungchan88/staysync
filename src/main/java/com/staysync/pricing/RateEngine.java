package com.staysync.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * 기준가에 규칙을 적용해 판매가를 낸다. 계획서 8.4.
 *
 * <p><b>순수 계산이다.</b> 데이터베이스도 시계도 보지 않는다. 오늘 날짜가 필요한
 * 임박 규칙조차 {@link RateContext#daysUntilCheckIn()} 로 미리 계산해 받는다.
 * 그래서 이 프로젝트에서 가장 테스트하기 쉬운 부분이고, 경계값을 아끼지 않았다.
 *
 * <p>순서는 계획서대로다 — 우선순위 오름차순으로 곱하고, 하한·상한으로 자르고,
 * 100원 단위로 반올림한다.
 *
 * <p><b>중간에 반올림하지 않는다.</b> 규칙마다 반올림하면 적용 순서가 결과를 바꾸고,
 * 우선순위가 같은 규칙 둘이 들어왔을 때 답이 둘이 된다. 끝에서 한 번만 반올림하면
 * 곱셈의 교환법칙이 그대로 성립해 <b>같은 우선순위 안에서는 순서가 결과를 바꾸지
 * 않는다.</b> 완료 조건 11 이 요구하는 "정의된 동작"이 이것이다.
 */
public final class RateEngine {

    /** 표시 단위. 계획서 8.4 가 100원이라고 적었다. */
    private static final int ROUNDING_UNIT = 100;

    private RateEngine() {
    }

    /**
     * 판매가를 낸다.
     *
     * @param basePrice 기준가. 셀마다 다르다
     * @param rules     적용할 규칙. 순서는 상관없다 — 여기서 우선순위로 정렬한다
     * @param context   이 셀의 문맥
     * @param bounds    하한·상한. 자르지 않으려면 {@link RateBounds#NONE}
     */
    public static BigDecimal price(BigDecimal basePrice, List<RateRule> rules,
                                   RateContext context, RateBounds bounds) {
        if (basePrice == null || basePrice.signum() < 0) {
            throw new IllegalArgumentException("기준가는 0 이상이어야 합니다. basePrice=" + basePrice);
        }

        BigDecimal price = basePrice;
        for (RateRule rule : sortedByPriority(rules)) {
            if (rule.matches(context)) {
                price = price.multiply(rule.multiplier());
            }
        }

        return roundWithin(clamp(price, bounds), bounds);
    }

    /**
     * 우선순위 오름차순. 같은 값이면 들어온 순서를 유지한다.
     *
     * <p>{@code List.sort} 가 안정 정렬이라 같은 우선순위의 상대 순서가 보존된다.
     * 결과는 어차피 같지만(전부 곱셈이므로), 정렬이 불안정하면 로그와 디버깅에서
     * 같은 입력이 매번 다르게 보인다.
     */
    private static List<RateRule> sortedByPriority(List<RateRule> rules) {
        List<RateRule> sorted = new java.util.ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(RateRule::priority));
        return sorted;
    }

    private static BigDecimal clamp(BigDecimal price, RateBounds bounds) {
        BigDecimal clamped = price;
        if (bounds.min() != null && clamped.compareTo(bounds.min()) < 0) {
            clamped = bounds.min();
        }
        if (bounds.max() != null && clamped.compareTo(bounds.max()) > 0) {
            clamped = bounds.max();
        }
        return clamped;
    }

    /**
     * 100원 단위로 반올림하되 <b>경계를 넘지 않는다.</b>
     *
     * <p>자른 다음 반올림하면 경계 자체가 100원 배수가 아닐 때 결과가 경계 밖으로
     * 나간다. 상한이 123,450원인데 반올림해서 123,500원이 되는 식이다. 100원 차이지만
     * "이 값을 넘지 않는다"는 약속이 깨지는 것이라, 넘은 쪽으로는 100원 단위로 한 칸
     * 물러선다. 그래도 하한이 상한보다 클 수는 없으므로({@link RateBounds}) 두 조건이
     * 동시에 깨지지는 않는다.
     */
    private static BigDecimal roundWithin(BigDecimal price, RateBounds bounds) {
        BigDecimal rounded = price.setScale(-2, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.UNNECESSARY);

        if (bounds.max() != null && rounded.compareTo(bounds.max()) > 0) {
            rounded = floorToUnit(bounds.max());
        }
        if (bounds.min() != null && rounded.compareTo(bounds.min()) < 0) {
            rounded = ceilToUnit(bounds.min());
        }
        return rounded;
    }

    private static BigDecimal floorToUnit(BigDecimal value) {
        return value.setScale(-2, RoundingMode.FLOOR).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal ceilToUnit(BigDecimal value) {
        return value.setScale(-2, RoundingMode.CEILING).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** 100원 단위인지. 테스트와 호출부가 확인용으로 쓴다. */
    public static boolean isRounded(BigDecimal price) {
        return price.remainder(BigDecimal.valueOf(ROUNDING_UNIT)).signum() == 0;
    }
}
