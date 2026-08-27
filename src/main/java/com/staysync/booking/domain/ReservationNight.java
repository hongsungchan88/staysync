package com.staysync.booking.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 박 단위 스냅샷. 캘린더 렌더링과 리포트 집계를 위해 비정규화한 행이다.
 *
 * <p>지금은 총액을 박 수로 나눠 채운다. P2 에서 {@code rate_calendar} 가 채워지면 실제
 * 박별 요금으로 바뀐다. 그때까지 중요한 것은 박별 값의 정확도가 아니라
 * <b>합계가 {@code total_amount} 와 정확히 같다</b>는 것이다. 반올림으로 어긋나면
 * 리포트 집계가 예약 총액과 달라지고, 그 차이는 나중에 원인을 찾기 어렵다.
 */
@Entity
@Table(name = "reservation_night")
@IdClass(ReservationNightId.class)
public class ReservationNight {

    @Id
    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Id
    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(nullable = false)
    private BigDecimal price = BigDecimal.ZERO;

    protected ReservationNight() {
    }

    public ReservationNight(Long reservationId, LocalDate stayDate, Long unitId, BigDecimal price) {
        this.reservationId = reservationId;
        this.stayDate = stayDate;
        this.unitId = unitId;
        this.price = price;
    }

    /**
     * 총액을 박 수로 나눠 박별 행을 만든다.
     *
     * <p>나누어떨어지지 않는 나머지는 첫 박에 붙인다(작업지시 02 의 5절 4번). 나머지를
     * 버리거나 각 박을 반올림하면 합이 총액과 어긋난다. 100,000 원을 3박으로 나누면
     * 33,333.33... 이 되는데, 이걸 그대로 세 번 더해도 100,000 이 되지 않는다.
     *
     * <p>금액은 {@code NUMERIC(12,2)} 라 소수점 둘째 자리까지다. 그 단위로 내림한 값을
     * 모든 박에 주고, 남은 차액을 첫 박에 얹는다.
     */
    public static List<ReservationNight> split(Long reservationId, Long unitId,
                                               StayPeriod period, BigDecimal totalAmount) {
        List<LocalDate> nights = period.nightDates();
        BigDecimal total = totalAmount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal each = total.divide(BigDecimal.valueOf(nights.size()), 2, RoundingMode.DOWN);
        BigDecimal remainder = total.subtract(each.multiply(BigDecimal.valueOf(nights.size())));

        List<ReservationNight> rows = new ArrayList<>(nights.size());
        for (int i = 0; i < nights.size(); i++) {
            BigDecimal price = i == 0 ? each.add(remainder) : each;
            rows.add(new ReservationNight(reservationId, nights.get(i), unitId, price));
        }
        return rows;
    }

    public LocalDate getStayDate() {
        return stayDate;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Long getUnitId() {
        return unitId;
    }
}
