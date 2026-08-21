package com.staysync.booking.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 예약. 채널에서 들어오거나 직접예약 또는 수기 입력으로 만들어진다. */
@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(name = "rate_plan_id")
    private Long ratePlanId;

    @Column(name = "guest_id")
    private Long guestId;

    @Embedded
    private StayPeriod period;

    @Column(name = "channel_code", nullable = false, length = 40)
    private String channelCode;

    /** OTA 측 예약번호. 채널 코드와 묶여 멱등성 키가 된다. */
    @Column(name = "channel_booking_id", length = 120)
    private String channelBookingId;

    @Column(name = "confirmation_code", nullable = false, length = 20)
    private String confirmationCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(nullable = false)
    private short adults = 2;

    @Column(nullable = false)
    private short children = 0;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "channel_commission", nullable = false)
    private BigDecimal channelCommission = BigDecimal.ZERO;

    /** 데이터베이스가 계산하는 생성 컬럼이라 애플리케이션에서는 읽기만 한다. */
    @Column(name = "net_amount", insertable = false, updatable = false)
    private BigDecimal netAmount;

    /** OTA 예약 수정 버전. 전달 순서가 뒤바뀌어도 최신 상태를 유지하기 위해 쓴다. */
    @Column(nullable = false)
    private int revision = 1;

    @Column(name = "hold_expires_at")
    private OffsetDateTime holdExpiresAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected Reservation() {
    }

    private Reservation(Long propertyId, Long unitId, StayPeriod period,
                        String channelCode, String confirmationCode, ReservationStatus status) {
        this.propertyId = propertyId;
        this.unitId = unitId;
        this.period = period;
        this.channelCode = channelCode;
        this.confirmationCode = confirmationCode;
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    /** 채널에서 수신한 예약. 이미 성사된 건이므로 바로 확정 상태로 만든다. */
    public static Reservation fromChannel(Long propertyId, Long unitId, StayPeriod period,
                                          String channelCode, String channelBookingId,
                                          String confirmationCode, int revision,
                                          BigDecimal totalAmount, BigDecimal commissionRate) {
        Reservation r = new Reservation(propertyId, unitId, period,
                channelCode, confirmationCode, ReservationStatus.CONFIRMED);
        r.channelBookingId = channelBookingId;
        r.revision = revision;
        r.totalAmount = totalAmount;
        r.channelCommission = totalAmount.multiply(commissionRate);
        return r;
    }

    /** 직접예약. 결제 완료 전까지 임시 점유 상태로 둔다. */
    public static Reservation directHold(Long propertyId, Long unitId, StayPeriod period,
                                         String confirmationCode, BigDecimal totalAmount,
                                         OffsetDateTime expiresAt) {
        Reservation r = new Reservation(propertyId, unitId, period,
                "DIRECT", confirmationCode, ReservationStatus.HOLD);
        r.totalAmount = totalAmount;
        r.holdExpiresAt = expiresAt;
        return r;
    }

    public Long getId() {
        return id;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getUnitId() {
        return unitId;
    }

    public StayPeriod getPeriod() {
        return period;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public String getChannelBookingId() {
        return channelBookingId;
    }

    public String getConfirmationCode() {
        return confirmationCode;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public int getRevision() {
        return revision;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public boolean isActive() {
        return status == ReservationStatus.HOLD || status == ReservationStatus.CONFIRMED;
    }

    /** 임시 점유를 확정으로 승격한다. 결제 성공 시 호출한다. */
    public void confirm() {
        if (status != ReservationStatus.HOLD) {
            throw new IllegalReservationTransition(status, ReservationStatus.CONFIRMED);
        }
        this.status = ReservationStatus.CONFIRMED;
        this.holdExpiresAt = null;
        touch();
    }

    /** 취소한다. 이미 취소된 예약을 다시 취소해도 오류가 아니다(멱등). */
    public void cancel() {
        if (status == ReservationStatus.CANCELLED) {
            return;
        }
        if (status == ReservationStatus.CHECKED_OUT) {
            throw new IllegalReservationTransition(status, ReservationStatus.CANCELLED);
        }
        this.status = ReservationStatus.CANCELLED;
        touch();
    }

    public void checkIn() {
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalReservationTransition(status, ReservationStatus.CHECKED_IN);
        }
        this.status = ReservationStatus.CHECKED_IN;
        touch();
    }

    public void checkOut() {
        if (status != ReservationStatus.CHECKED_IN) {
            throw new IllegalReservationTransition(status, ReservationStatus.CHECKED_OUT);
        }
        this.status = ReservationStatus.CHECKED_OUT;
        touch();
    }

    public void expire() {
        if (status != ReservationStatus.HOLD) {
            return;
        }
        this.status = ReservationStatus.EXPIRED;
        touch();
    }

    /**
     * OTA 에서 수정된 예약을 다시 받았을 때 반영한다.
     *
     * <p>전달 순서가 뒤바뀌어 이전 버전이 나중에 도착할 수 있으므로,
     * 더 높은 버전일 때만 적용한다.
     *
     * @return 실제로 반영했으면 true
     */
    public boolean applyRevision(int incomingRevision, StayPeriod newPeriod, BigDecimal newAmount) {
        if (incomingRevision <= this.revision) {
            return false;
        }
        this.period = newPeriod;
        this.totalAmount = newAmount;
        this.revision = incomingRevision;
        touch();
        return true;
    }

    public boolean isHoldExpired(OffsetDateTime now) {
        return status == ReservationStatus.HOLD
                && holdExpiresAt != null
                && holdExpiresAt.isBefore(now);
    }

    public boolean coversDate(LocalDate date) {
        return !date.isBefore(period.checkIn()) && date.isBefore(period.checkOut());
    }

    private void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
