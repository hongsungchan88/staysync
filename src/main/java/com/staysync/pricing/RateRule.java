package com.staysync.pricing;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * 기준가에 곱해지는 요금 규칙. 계획서 8.4 의 정의를 그대로 옮겼다.
 *
 * <p><b>{@code sealed} 인 것이 핵심이다.</b> P5 의 AI 요금 추천이 규칙을 더할 때,
 * 분기를 빠뜨리면 컴파일러가 잡아 준다. 열려 있으면 새 규칙이 조용히 무시되고
 * 요금이 왜 그 값인지 설명할 수 없게 된다.
 *
 * <p><b>모든 규칙은 곱셈이다.</b> 할인율도 {@code 1 - rate} 로 바뀌어 곱해진다.
 * 이 성질 덕분에 같은 우선순위 안에서는 적용 순서가 결과를 바꾸지 않는다
 * ({@link RateEngine} 참조). 나중에 덧셈 규칙을 더하려 한다면 그 성질이 깨지므로
 * 그때 순서 규칙을 명시해야 한다.
 *
 * <p><b>저장하지 않는다.</b> 규칙을 담는 테이블도 CRUD 도 만들지 않았다. 저장된 규칙을
 * 읽는 곳이 아직 없기 때문이다(작업지시 06 의 5절 2번). 지금 실사용처는 일괄 편집의
 * "기존 대비 ±%" 하나이고, P5 가 이 위에 얹힌다.
 */
public sealed interface RateRule {

    /** 낮을수록 먼저 적용된다. 같은 값이어도 결과는 같다 — 전부 곱셈이기 때문이다. */
    int priority();

    /** 이 셀에 해당하는 규칙인지. */
    boolean matches(RateContext context);

    /** 곱할 배수. {@link #matches} 가 참일 때만 쓰인다. */
    BigDecimal multiplier();

    /** 시즌 — 기간에 드는 날에 배수를 적용한다. 성수기 할증이 여기다. */
    record SeasonRule(LocalDate from, LocalDate to, BigDecimal multiplier, int priority)
            implements RateRule {

        public SeasonRule {
            if (from == null || to == null || to.isBefore(from)) {
                throw new IllegalArgumentException(
                        "시즌 기간이 뒤집혔습니다. from=%s to=%s".formatted(from, to));
            }
            requirePositive(multiplier);
        }

        @Override
        public boolean matches(RateContext context) {
            LocalDate date = context.date();
            return !date.isBefore(from) && !date.isAfter(to);
        }
    }

    /** 요일 — 주말 할증이 여기다. */
    record DayOfWeekRule(Set<DayOfWeek> days, BigDecimal multiplier, int priority)
            implements RateRule {

        public DayOfWeekRule {
            days = Set.copyOf(days);   // 바깥에서 나중에 고쳐도 규칙이 바뀌지 않게 한다
            requirePositive(multiplier);
        }

        @Override
        public boolean matches(RateContext context) {
            return days.contains(context.date().getDayOfWeek());
        }
    }

    /** 숙박일수 — 길게 묵으면 깎아 준다. */
    record LengthOfStayRule(int minNights, BigDecimal discountRate, int priority)
            implements RateRule {

        public LengthOfStayRule {
            requireRate(discountRate);
        }

        @Override
        public boolean matches(RateContext context) {
            return context.nights() >= minNights;
        }

        @Override
        public BigDecimal multiplier() {
            return discountToMultiplier(discountRate);
        }
    }

    /** 임박 — 체크인이 코앞이면 깎아서라도 판다. */
    record LastMinuteRule(int withinDays, BigDecimal discountRate, int priority)
            implements RateRule {

        public LastMinuteRule {
            requireRate(discountRate);
        }

        @Override
        public boolean matches(RateContext context) {
            // 이미 지난 날짜(음수)도 임박으로 본다. 과거 날짜에 규칙을 적용할 일은 없지만,
            // 경계에서 조용히 어긋나는 것보다 정의가 있는 편이 낫다.
            return context.daysUntilCheckIn() <= withinDays;
        }

        @Override
        public BigDecimal multiplier() {
            return discountToMultiplier(discountRate);
        }
    }

    /** 점유율 — 많이 찼으면 올려 받는다. */
    record OccupancyRule(BigDecimal thresholdRate, BigDecimal multiplier, int priority)
            implements RateRule {

        public OccupancyRule {
            requireRate(thresholdRate);
            requirePositive(multiplier);
        }

        @Override
        public boolean matches(RateContext context) {
            return context.occupancyRate().compareTo(thresholdRate) >= 0;
        }
    }

    private static BigDecimal discountToMultiplier(BigDecimal discountRate) {
        return BigDecimal.ONE.subtract(discountRate);
    }

    private static void requirePositive(BigDecimal multiplier) {
        if (multiplier == null || multiplier.signum() <= 0) {
            throw new IllegalArgumentException("배수는 0보다 커야 합니다. multiplier=" + multiplier);
        }
    }

    /** 0 이상 1 이하. 할인율 1.2 나 -0.3 은 입력 실수이지 의도가 아니다. */
    private static void requireRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("비율은 0 과 1 사이여야 합니다. rate=" + rate);
        }
    }
}
