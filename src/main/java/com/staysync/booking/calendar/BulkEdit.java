package com.staysync.booking.calendar;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 요금·제약 일괄 편집의 요청과 결과. 계획서 8.4 의 편집 패널이 명세다.
 *
 * <p>바꾸지 않을 항목은 {@code null} 이다. 패널에서 요금만 건드리고 최소 숙박은
 * 그대로 두는 것이 흔한 조작이라, "전부 넘기고 같은 값을 다시 쓰기"로는 표현이 안 된다.
 */
public final class BulkEdit {

    private BulkEdit() {
    }

    /** 요금을 어떻게 바꿀지. 계획서 8.4 의 "고정값 / 기존 대비 ±%" 두 가지다. */
    public sealed interface PriceChange {

        /** 고른 셀을 전부 이 값으로 만든다. */
        record Fixed(BigDecimal price) implements PriceChange {
            public Fixed {
                if (price == null || price.signum() < 0) {
                    throw new IllegalArgumentException("요금은 0 이상이어야 합니다. price=" + price);
                }
            }
        }

        /**
         * 셀의 기존 요금에 비율을 적용한다. {@code +20%} 면 {@code 0.20} 이다.
         *
         * <p><b>기준가는 셀마다 다르다.</b> 한 번 읽어 전부에 같은 값을 쓰면 요일마다
         * 다르게 매겨 둔 요금이 하나로 뭉개진다.
         */
        record Percent(BigDecimal rate) implements PriceChange {
            public Percent {
                if (rate == null) {
                    throw new IllegalArgumentException("비율이 필요합니다.");
                }
                if (rate.compareTo(BigDecimal.ONE.negate()) <= 0) {
                    // -100% 이하면 요금이 0 이하가 된다. 무료로 파는 것은 이 화면의 조작이 아니다.
                    throw new IllegalArgumentException("비율은 -100% 보다 커야 합니다. rate=" + rate);
                }
            }

            /** 규칙 엔진에 넘길 배수. {@code +20%} 는 {@code 1.20} 이다. */
            public BigDecimal multiplier() {
                return BigDecimal.ONE.add(rate);
            }
        }
    }

    /**
     * 편집 요청.
     *
     * @param unitIds         적용 대상 판매 단위. 여럿이다
     * @param from            시작일(포함)
     * @param to              종료일(포함)
     * @param weekdays        요일 필터. 비어 있으면 전체 요일
     * @param price           요금 변경. {@code null} 이면 요금을 건드리지 않는다
     * @param minStay         최소 숙박일. {@code null} 이면 그대로
     * @param closedToArrival 체크인 금지. {@code null} 이면 그대로
     * @param stopSell        판매 중지. {@code null} 이면 그대로
     * @param dryRun          참이면 무엇이 바뀔지만 세고 아무것도 쓰지 않는다
     */
    public record Request(
            List<Long> unitIds,
            LocalDate from,
            LocalDate to,
            Set<DayOfWeek> weekdays,
            PriceChange price,
            Short minStay,
            Boolean closedToArrival,
            Boolean stopSell,
            boolean dryRun) {

        public Request {
            if (unitIds == null || unitIds.isEmpty()) {
                throw new InvalidBulkEditException("적용 대상 판매 단위를 지정해야 합니다.");
            }
            if (from == null || to == null || to.isBefore(from)) {
                throw new InvalidBulkEditException(
                        "기간이 올바르지 않습니다. from=%s to=%s".formatted(from, to));
            }
            if (minStay != null && minStay < 1) {
                throw new InvalidBulkEditException("최소 숙박일은 1 이상이어야 합니다.");
            }
            unitIds = List.copyOf(unitIds);
            weekdays = weekdays == null ? Set.of() : Set.copyOf(weekdays);

            if (price == null && minStay == null && closedToArrival == null && stopSell == null) {
                // 아무것도 바꾸지 않는 요청은 성공으로 답하면 "적용됐다"고 읽힌다.
                throw new InvalidBulkEditException("바꿀 항목이 하나도 없습니다.");
            }
        }

        /** 요금·최소숙박·체크인금지 중 하나라도 바꾸면 rate_calendar 를 건드린다. */
        boolean touchesRates() {
            return price != null || minStay != null || closedToArrival != null;
        }

        boolean matchesWeekday(LocalDate date) {
            return weekdays.isEmpty() || weekdays.contains(date.getDayOfWeek());
        }
    }

    /**
     * 편집 결과.
     *
     * <p>{@code dryRun} 이든 아니든 <b>같은 계산으로 나온 값이다.</b> 미리보기와 적용이
     * 다른 코드였다면 "미리보기에 20건이라 했는데 22건이 바뀌었다"가 가능하고, 그건
     * 사용자가 적용한 뒤에야 안다(작업지시 06 의 5절 3번).
     *
     * @param unitCount  대상 판매 단위 수
     * @param dayCount   요일 필터를 거친 날짜 수
     * @param cellCount  바뀌는 셀 수. {@code unitCount × dayCount}
     * @param changed    바뀐 항목 이름. 감사 기록과 화면 요약이 함께 쓴다
     * @param dryRun     미리보기였는지
     */
    public record Result(
            int unitCount,
            int dayCount,
            int cellCount,
            List<String> changed,
            boolean dryRun) {
    }
}
