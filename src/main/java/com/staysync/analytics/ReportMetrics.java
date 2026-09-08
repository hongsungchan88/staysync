package com.staysync.analytics;

import java.math.BigDecimal;
import java.util.List;

/**
 * 리포트 지표. 계획서 8.8 이 여덟을 적었고 <b>여섯을 만든다.</b>
 *
 * <p>빠진 둘은 만들 수 없어서다(작업지시 13 의 5절 2번).
 *
 * <ul>
 *   <li><b>순수익률</b> — 채널 수수료와 결제 수수료를 빼야 하는데 그 둘이 기록되지
 *       않는다. {@code reservation.channel_commission} 은 항상 0 이고(유일한 기록
 *       경로가 0 을 넘긴다), 결제 수수료는 남는 자리 자체가 없다. 데이터가 없는데
 *       계산하면 <b>그럴듯한 숫자가 화면에 뜨고 그것이 보고서에 실린다</b></li>
 *   <li><b>진척도</b> — 전년 동일 시점 대비인데 전년 데이터가 없다. 이 프로젝트는
 *       2026년 8월에 시작했다</li>
 * </ul>
 *
 * <p><b>분모가 0인 경우가 정상이다.</b> 예약이 하나도 없는 기간을 조회하는 것은 흔한
 * 일이고, 그때 화면이 터지면 안 된다. 0으로 나누는 자리마다 0을 돌려준다 —
 * "값이 없다"와 "0이다"를 구분해 보여 주는 것은 화면의 몫이다.
 *
 * @param soldNights     판매된 객실박
 * @param availableNights 판매 가능 객실박. 판매 단위 수 × 기간 일수
 * @param roomRevenue    객실 매출
 * @param occupancyRate  점유율. 0.0 ~ 1.0
 * @param adr            객실 단가. 객실 매출 ÷ 판매된 객실박
 * @param revPar         가용 객실당 매출. 객실 매출 ÷ 판매 가능 객실박
 * @param leadTimeDays   예약일부터 체크인일까지 평균 일수
 * @param cancellationRate 취소율. 0.0 ~ 1.0
 * @param channelMix     채널별 건수와 매출
 */
public record ReportMetrics(
        long soldNights,
        long availableNights,
        BigDecimal roomRevenue,
        BigDecimal occupancyRate,
        BigDecimal adr,
        BigDecimal revPar,
        BigDecimal leadTimeDays,
        BigDecimal cancellationRate,
        List<ChannelShare> channelMix) {

    /**
     * 채널 하나의 몫.
     *
     * @param revenueShare 매출 비중. 0.0 ~ 1.0. 전체 매출이 0이면 0이다
     */
    public record ChannelShare(String channelCode, long reservations,
                               BigDecimal revenue, BigDecimal revenueShare) {
    }
}
