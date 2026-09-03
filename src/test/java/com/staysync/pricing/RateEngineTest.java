package com.staysync.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 완료 조건 9·10·11. 요금 규칙 엔진.
 *
 * <p>데이터베이스도 화면도 없이 도는 순수 계산이라 이 프로젝트에서 가장 테스트하기 쉬운
 * 부분이다. 작업지시 06 이 "경계값을 아끼지 말 것"이라고 한 자리이고, 실제로 요금은
 * 틀려도 예외가 나지 않는다 — 그냥 잘못된 값에 팔린다.
 */
class RateEngineTest {

    private static final LocalDate 금요일 = LocalDate.of(2026, 12, 25);
    private static final BigDecimal 기준가 = new BigDecimal("100000.00");

    private static RateContext 문맥(LocalDate date) {
        return new RateContext(date, 3, 30, new BigDecimal("0.50"));
    }

    private static BigDecimal 원(String value) {
        return new BigDecimal(value);
    }

    // --- 완료 조건 9 ---------------------------------------------------------

    @Test
    @DisplayName("다섯 규칙이 우선순위 순으로 전부 적용된다")
    void 다섯_규칙이_우선순위_순으로_적용된다() {
        List<RateRule> rules = List.of(
                // 일부러 우선순위를 뒤섞어 넣는다. 엔진이 정렬해야 한다.
                new RateRule.OccupancyRule(원("0.40"), 원("1.10"), 5),
                new RateRule.SeasonRule(금요일.minusDays(1), 금요일.plusDays(1), 원("2.00"), 1),
                new RateRule.LastMinuteRule(60, 원("0.10"), 4),
                new RateRule.DayOfWeekRule(Set.of(DayOfWeek.FRIDAY), 원("1.50"), 2),
                new RateRule.LengthOfStayRule(3, 원("0.20"), 3));

        BigDecimal price = RateEngine.price(기준가, rules, 문맥(금요일), RateBounds.NONE);

        // 100000 × 2.0 × 1.5 × 0.8 × 0.9 × 1.1 = 237,600
        assertThat(price).isEqualByComparingTo(원("237600"));
    }

    @Test
    @DisplayName("조건에 맞지 않는 규칙은 건너뛴다")
    void 맞지_않는_규칙은_적용되지_않는다() {
        LocalDate 수요일 = LocalDate.of(2026, 12, 23);
        List<RateRule> rules = List.of(
                new RateRule.DayOfWeekRule(Set.of(DayOfWeek.FRIDAY), 원("1.50"), 1),
                new RateRule.SeasonRule(금요일, 금요일, 원("2.00"), 2),
                new RateRule.LengthOfStayRule(10, 원("0.20"), 3));

        // 수요일이고, 시즌 밖이고, 3박이라 최소 10박에 못 미친다. 하나도 걸리지 않는다.
        BigDecimal price = RateEngine.price(기준가, rules, 문맥(수요일), RateBounds.NONE);

        assertThat(price).isEqualByComparingTo(기준가);
    }

    @Test
    void 규칙이_없으면_기준가_그대로다() {
        assertThat(RateEngine.price(기준가, List.of(), 문맥(금요일), RateBounds.NONE))
                .isEqualByComparingTo(기준가);
    }

    @Test
    @DisplayName("점유율과 임박은 경계값에서 포함된다")
    void 점유율과_임박은_경계에서_걸린다() {
        RateContext 경계 = new RateContext(금요일, 3, 30, 원("0.40"));

        // 임계와 정확히 같으면 적용된다. 이상/이하를 초과/미만으로 잘못 쓰면 여기서 갈린다.
        BigDecimal 점유율 = RateEngine.price(기준가,
                List.of(new RateRule.OccupancyRule(원("0.40"), 원("1.10"), 1)),
                경계, RateBounds.NONE);
        assertThat(점유율).isEqualByComparingTo(원("110000"));

        BigDecimal 임박 = RateEngine.price(기준가,
                List.of(new RateRule.LastMinuteRule(30, 원("0.10"), 1)),
                경계, RateBounds.NONE);
        assertThat(임박).isEqualByComparingTo(원("90000"));

        // 하루만 벗어나면 걸리지 않는다.
        BigDecimal 밖 = RateEngine.price(기준가,
                List.of(new RateRule.LastMinuteRule(29, 원("0.10"), 1)),
                경계, RateBounds.NONE);
        assertThat(밖).isEqualByComparingTo(기준가);
    }

    @Test
    @DisplayName("시즌은 양끝 날짜를 포함한다")
    void 시즌은_양끝을_포함한다() {
        RateRule 시즌 = new RateRule.SeasonRule(금요일, 금요일.plusDays(2), 원("2.00"), 1);

        assertThat(RateEngine.price(기준가, List.of(시즌), 문맥(금요일), RateBounds.NONE))
                .isEqualByComparingTo(원("200000"));
        assertThat(RateEngine.price(기준가, List.of(시즌), 문맥(금요일.plusDays(2)), RateBounds.NONE))
                .isEqualByComparingTo(원("200000"));
        assertThat(RateEngine.price(기준가, List.of(시즌), 문맥(금요일.minusDays(1)), RateBounds.NONE))
                .isEqualByComparingTo(기준가);
        assertThat(RateEngine.price(기준가, List.of(시즌), 문맥(금요일.plusDays(3)), RateBounds.NONE))
                .isEqualByComparingTo(기준가);
    }

    // --- 완료 조건 10 --------------------------------------------------------

    @Test
    @DisplayName("하한과 상한으로 잘린다")
    void 하한과_상한으로_잘린다() {
        RateBounds bounds = new RateBounds(원("80000"), 원("150000"));

        // 배수를 크게 넣어도 상한에서 멈춘다. 곱셈 규칙의 마지막 안전장치다.
        BigDecimal 위 = RateEngine.price(기준가,
                List.of(new RateRule.SeasonRule(금요일, 금요일, 원("10.00"), 1)),
                문맥(금요일), bounds);
        assertThat(위).isEqualByComparingTo(원("150000"));

        BigDecimal 아래 = RateEngine.price(기준가,
                List.of(new RateRule.LengthOfStayRule(1, 원("0.90"), 1)),
                문맥(금요일), bounds);
        assertThat(아래).isEqualByComparingTo(원("80000"));
    }

    @Test
    @DisplayName("100원 단위로 반올림한다 — 반올림 경계 포함")
    void 백원_단위로_반올림한다() {
        // 50원은 올린다(HALF_UP).
        assertThat(RateEngine.price(원("100050"), List.of(), 문맥(금요일), RateBounds.NONE))
                .isEqualByComparingTo(원("100100"));
        // 49원은 내린다.
        assertThat(RateEngine.price(원("100049"), List.of(), 문맥(금요일), RateBounds.NONE))
                .isEqualByComparingTo(원("100000"));
        // 곱셈이 만든 소수점도 정리된다. 100000 × 1.333 = 133,300
        assertThat(RateEngine.price(기준가,
                List.of(new RateRule.SeasonRule(금요일, 금요일, 원("1.3333"), 1)),
                문맥(금요일), RateBounds.NONE))
                .isEqualByComparingTo(원("133300"));
    }

    @Test
    @DisplayName("반올림이 경계를 넘지 않는다")
    void 반올림해도_경계를_넘지_않는다() {
        // 상한이 100원 배수가 아니면, 자른 뒤 반올림할 때 상한을 넘길 수 있다.
        // 123,450 을 반올림하면 123,500 이 되어 "넘지 않는다"는 약속이 깨진다.
        RateBounds 상한 = new RateBounds(null, 원("123450"));
        BigDecimal 위 = RateEngine.price(기준가,
                List.of(new RateRule.SeasonRule(금요일, 금요일, 원("10.00"), 1)),
                문맥(금요일), 상한);
        assertThat(위).isEqualByComparingTo(원("123400"));
        assertThat(위).isLessThanOrEqualTo(원("123450"));

        // 하한도 마찬가지다. 내림하면 하한 아래로 내려간다.
        RateBounds 하한 = new RateBounds(원("123450"), null);
        BigDecimal 아래 = RateEngine.price(원("10000"), List.of(), 문맥(금요일), 하한);
        assertThat(아래).isEqualByComparingTo(원("123500"));
        assertThat(아래).isGreaterThanOrEqualTo(원("123450"));
    }

    @Test
    @DisplayName("하한이 상한보다 크면 거부한다")
    void 하한이_상한보다_크면_거부한다() {
        // 조용히 한쪽을 이기게 두면 설정 실수가 요금으로 드러나고, 그때는 이미 팔린 뒤다.
        assertThatThrownBy(() -> new RateBounds(원("200000"), 원("100000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("하한이 상한보다");
    }

    // --- 완료 조건 11 --------------------------------------------------------

    @Test
    @DisplayName("우선순위가 같은 규칙 둘은 순서와 무관하게 같은 결과를 낸다")
    void 같은_우선순위는_순서가_결과를_바꾸지_않는다() {
        RateRule 금요일할증 = new RateRule.DayOfWeekRule(Set.of(DayOfWeek.FRIDAY), 원("1.50"), 7);
        RateRule 시즌할증 = new RateRule.SeasonRule(금요일, 금요일, 원("1.30"), 7);

        BigDecimal 정방향 = RateEngine.price(기준가, List.of(금요일할증, 시즌할증),
                문맥(금요일), RateBounds.NONE);
        BigDecimal 역방향 = RateEngine.price(기준가, List.of(시즌할증, 금요일할증),
                문맥(금요일), RateBounds.NONE);

        // 이것이 완료 조건 11 이 요구하는 "정의된 동작"이다. 전부 곱셈이고 중간에
        // 반올림하지 않으므로 교환법칙이 성립한다. 규칙마다 반올림하면 여기서 갈린다.
        assertThat(정방향).isEqualByComparingTo(역방향);
        // 100000 × 1.5 × 1.3 = 195,000. 둘 다 적용되지 하나만 이기는 것이 아니다.
        assertThat(정방향).isEqualByComparingTo(원("195000"));
    }

    @Test
    @DisplayName("우선순위가 다르면 낮은 쪽이 먼저 적용된다")
    void 우선순위가_낮은_쪽이_먼저다() {
        // 곱셈만 있으면 순서가 결과를 바꾸지 않으므로, 순서 자체는 상한이 걸릴 때 드러난다.
        // 2배 뒤 상한 150,000 에 걸리고, 그 뒤 0.5 배가 적용돼 75,000 이 되지는 않는다 —
        // 자르기는 규칙을 다 적용한 뒤 한 번만이기 때문이다.
        List<RateRule> rules = List.of(
                new RateRule.SeasonRule(금요일, 금요일, 원("2.00"), 1),
                new RateRule.LengthOfStayRule(1, 원("0.50"), 2));

        BigDecimal price = RateEngine.price(기준가, rules, 문맥(금요일),
                new RateBounds(null, 원("150000")));

        // 100000 × 2.0 × 0.5 = 100,000. 상한에 닿지 않는다.
        assertThat(price).isEqualByComparingTo(원("100000"));
    }

    // --- 입력 검증 -----------------------------------------------------------

    @Test
    void 잘못된_입력은_만들_때_거부한다() {
        assertThatThrownBy(() -> new RateRule.SeasonRule(금요일, 금요일.minusDays(1), 원("1.5"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("시즌 기간이 뒤집혔");

        assertThatThrownBy(() -> new RateRule.LengthOfStayRule(3, 원("1.5"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 과 1 사이");

        assertThatThrownBy(() -> new RateRule.DayOfWeekRule(Set.of(DayOfWeek.FRIDAY),원("0"), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0보다 커야");

        assertThatThrownBy(() -> RateEngine.price(원("-1"), List.of(), 문맥(금요일), RateBounds.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("기준가는 0 이상");
    }

    @Test
    @DisplayName("결과는 항상 100원 단위다")
    void 결과는_항상_백원_단위다() {
        for (String base : List.of("33333", "99999", "100001", "77777.77")) {
            BigDecimal price = RateEngine.price(원(base),
                    List.of(new RateRule.SeasonRule(금요일, 금요일, 원("1.37"), 1)),
                    문맥(금요일), RateBounds.NONE);
            assertThat(RateEngine.isRounded(price))
                    .as("기준가 %s 의 결과 %s 가 100원 단위여야 한다", base, price)
                    .isTrue();
        }
    }
}
