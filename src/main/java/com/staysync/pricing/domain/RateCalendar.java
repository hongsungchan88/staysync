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
 * <p>P2 7주차에는 <b>읽기만</b> 한다. 요금 편집은 9주차다. 그래서 상태를 바꾸는
 * 메서드를 두지 않았다. 필요해지는 시점에 의미 있는 이름으로 더한다.
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
}
