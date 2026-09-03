package com.staysync.pricing;

import java.math.BigDecimal;

/**
 * 요금의 하한과 상한. 규칙을 다 적용한 뒤 여기서 자른다.
 *
 * <p>둘 다 {@code null} 이면 자르지 않는다. 규칙이 곱셈이라 배수를 잘못 넣으면 요금이
 * 몇 배로 튀는데, 그때 마지막으로 막는 것이 이 값이다.
 */
public record RateBounds(BigDecimal min, BigDecimal max) {

    /** 자르지 않는다. 일괄 편집처럼 사용자가 값을 직접 정하는 자리에서 쓴다. */
    public static final RateBounds NONE = new RateBounds(null, null);

    public RateBounds {
        if (min != null && min.signum() < 0) {
            throw new IllegalArgumentException("하한은 음수일 수 없습니다. min=" + min);
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            // 조용히 한쪽을 이기게 두면 설정 실수가 요금으로 드러난다. 그때는 이미 팔린 뒤다.
            throw new IllegalArgumentException(
                    "하한이 상한보다 큽니다. min=%s max=%s".formatted(min, max));
        }
    }
}
