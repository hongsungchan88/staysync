package com.staysync.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 규칙이 판단에 쓰는 값. 셀 하나에 하나다.
 *
 * <p>규칙이 데이터베이스를 보지 않게 하려고 필요한 값을 미리 담아 넘긴다. 그래서
 * {@link RateEngine} 전체가 순수 계산이 되고, 데이터베이스도 화면도 없이 테스트할 수 있다.
 *
 * @param date              대상 날짜
 * @param nights            숙박일수. 숙박일수 규칙이 쓴다
 * @param daysUntilCheckIn  오늘부터 체크인까지 남은 일수. 임박 규칙이 쓴다
 * @param occupancyRate     0.0 ~ 1.0 점유율. 점유율 규칙이 쓴다
 */
public record RateContext(
        LocalDate date,
        int nights,
        long daysUntilCheckIn,
        BigDecimal occupancyRate) {

    public RateContext {
        if (date == null) {
            throw new IllegalArgumentException("날짜가 필요합니다.");
        }
        if (occupancyRate == null) {
            occupancyRate = BigDecimal.ZERO;
        }
    }

    /**
     * 숙박일수·임박·점유율을 따지지 않는 자리에서 쓰는 최소 문맥.
     *
     * <p>일괄 편집의 "기존 대비 ±%" 가 이걸 쓴다. 그 조작은 날짜만 보면 되기 때문이다.
     */
    public static RateContext ofDate(LocalDate date) {
        return new RateContext(date, 1, Long.MAX_VALUE, BigDecimal.ZERO);
    }
}
