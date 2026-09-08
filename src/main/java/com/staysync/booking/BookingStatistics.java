package com.staysync.booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * booking 이 리포트를 위해 공개하는 집계. <b>읽기 전용이고 행이 아니라 합계를 돌려준다.</b>
 * P4 16주차에 열었다.
 *
 * <p>{@link ReservationDirectory} 와 나눈 이유는 성격이다. 저쪽은 예약 하나하나를
 * 돌려주고 인박스와 자동 발송이 쓴다. 여기는 <b>세어서 더한 값</b>만 돌려준다 —
 * 리포트가 6개월치 예약 행을 받아 자바에서 더하면 응답이 기간에 비례해 커지고,
 * 지표 여섯이 그 목록을 각각 다시 훑는다.
 *
 * <p><b>이 포트가 있는 이유가 하나 더 있다.</b> analytics 가 {@code reservation} 과
 * {@code reservation_night} 을 SQL 로 직접 읽으면 {@code ModularityTest} 는 통과한다 —
 * 그것은 타입 참조만 보기 때문이다. 그렇게 두면 <b>빌드가 잡아 주지 않는 경계 위반</b>이
 * 되고, 예약 스키마를 고칠 때 analytics 가 조용히 깨진다. 집계 질의는 그 테이블을
 * 가진 모듈이 들고 있어야 한다.
 *
 * <p><b>비율을 계산하지 않는다.</b> 분자와 분모를 주고 나누는 것은 analytics 의 몫이다.
 * 여기서 나누면 0으로 나누는 규칙이 두 모듈에 흩어진다.
 */
public interface BookingStatistics {

    /**
     * 판매된 객실박과 그 매출.
     *
     * @param nights  박 수
     * @param revenue 박별 금액의 합
     */
    record SoldNights(long nights, BigDecimal revenue) {
    }

    /**
     * 취소율의 분자와 분모.
     *
     * @param cancelled 취소된 예약 수
     * @param total     센 예약 수
     */
    record CancellationCounts(long cancelled, long total) {
    }

    /** 채널 하나의 건수와 매출. 비중은 부르는 쪽이 구한다. */
    record ChannelVolume(String channelCode, long reservations, BigDecimal revenue) {
    }

    /**
     * 그 기간에 묵은 박과 매출.
     *
     * <p>기간에 걸친 예약도 <b>그 기간에 든 박만</b> 센다. 박 행이 하루씩 쪼개져
     * 있어서 자르는 일이 따로 없다.
     */
    SoldNights soldNights(List<Long> propertyIds, LocalDate from, LocalDate to);

    /** 예약일부터 체크인일까지 평균 일수. 체크인이 기간 안인 예약이 단위다. */
    BigDecimal averageLeadTimeDays(List<Long> propertyIds, LocalDate from, LocalDate to);

    /**
     * 취소 건수와 전체 건수. <b>체크인 날짜</b> 기준이다.
     *
     * <p>리포트의 다른 지표와 기간의 뜻을 맞춘다. 예약일 기준으로 세면 다음 달
     * 리포트의 취소율이 언제나 0이 된다.
     */
    CancellationCounts cancellationCounts(List<Long> propertyIds, LocalDate from, LocalDate to);

    /** 채널별 건수와 매출. 박이 기간 안인 예약이 단위다. */
    List<ChannelVolume> channelVolumes(List<Long> propertyIds, LocalDate from, LocalDate to);
}
