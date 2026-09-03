package com.staysync.pricing.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 날짜별 요금과 판매 제약.
 *
 * <p>키가 {@code (rate_plan_id, stay_date)} 다. 판매 단위가 아니라 요금제에 붙는다.
 * Booking.com 과 Channex 가 ARI 전송의 최소 단위로 요금제를 요구하기 때문이며,
 * 그래서 판매 단위 하나에 기본 요금제 하나가 자동으로 붙는다.
 *
 * <p>P2 9주차에 쓰기가 붙었다. setter 를 두지 않고 {@link #edit} 하나로 받는다 —
 * 일괄 편집이 요금·최소숙박·체크인금지를 늘 함께 정하므로, 필드마다 열어 두면
 * 일부만 바뀐 중간 상태를 만들 수 있는 경로가 생긴다.
 */
@Entity
@Table(name = "rate_calendar")
@IdClass(RateCalendarId.class)
public class RateCalendar {

    @Id
    @Column(name = "rate_plan_id", nullable = false)
    private Long ratePlanId;

    @Id
    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;

    @Column(nullable = false)
    private BigDecimal price;

    /** 컬럼이 {@code SMALLINT} 라 {@code short} 여야 한다. {@code int} 면 검증이 막는다. */
    @Column(name = "min_stay", nullable = false)
    private short minStay = 1;

    @Column(name = "max_stay")
    private Short maxStay;

    @Column(name = "closed_to_arrival", nullable = false)
    private boolean closedToArrival = false;

    @Column(name = "closed_to_departure", nullable = false)
    private boolean closedToDeparture = false;

    /**
     * 요금제 쪽 판매중지.
     *
     * <p>{@code inventory_ledger} 에도 같은 이름의 컬럼이 있다. 캘린더 화면이 쓰는 것은
     * 원장 쪽이다. 출처가 둘이라는 걸 알고 있어야 한다.
     */
    @Column(name = "stop_sell", nullable = false)
    private boolean stopSell = false;

    @Column(nullable = false, length = 20)
    private String source = "MANUAL";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    protected RateCalendar() {
    }

    public RateCalendar(Long ratePlanId, LocalDate stayDate, BigDecimal price, short minStay) {
        this.ratePlanId = ratePlanId;
        this.stayDate = stayDate;
        this.price = price;
        this.minStay = minStay;
    }

    public Long getRatePlanId() {
        return ratePlanId;
    }

    public LocalDate getStayDate() {
        return stayDate;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public short getMinStay() {
        return minStay;
    }

    public boolean isStopSell() {
        return stopSell;
    }

    public boolean isClosedToArrival() {
        return closedToArrival;
    }

    /**
     * 일괄 편집이 셀을 이 값으로 만든다.
     *
     * <p>{@code source} 는 {@code MANUAL} 그대로다. 규칙이 자동으로 넣은 값과 사람이
     * 넣은 값을 구분하는 컬럼인데, 지금 경로는 사람이 화면에서 정한 것이다. 규칙 엔진이
     * 자동 적용되는 것은 P5 이고 그때 {@code RULE} 이 쓰인다.
     */
    public void edit(BigDecimal newPrice, short newMinStay, boolean newClosedToArrival) {
        if (newPrice == null || newPrice.signum() < 0) {
            throw new IllegalArgumentException("요금은 0 이상이어야 합니다. price=" + newPrice);
        }
        this.price = newPrice;
        this.minStay = newMinStay;
        this.closedToArrival = newClosedToArrival;
        this.updatedAt = OffsetDateTime.now();
    }
}
