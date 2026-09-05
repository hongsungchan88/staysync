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

    /**
     * 수기 예약. 전화로 받은 예약 등을 직접 등록한다.
     *
     * <p>채널 예약과 달리 바로 확정으로 만든다. 운영자가 이미 성사시킨 건이라 결제를
     * 기다리는 HOLD 단계가 없다.
     *
     * <p>{@code channelBookingId} 를 비워 둔다. {@code uq_channel_booking} 유니크 제약이
     * 걸려 있지만 PostgreSQL 은 NULL 을 서로 다른 값으로 보므로 수기 예약이 여러 건이어도
     * 충돌하지 않는다.
     */
    public static Reservation manual(Long propertyId, Long unitId, StayPeriod period,
                                     String confirmationCode, BigDecimal totalAmount,
                                     short adults, short children) {
        Reservation r = new Reservation(propertyId, unitId, period,
                "DIRECT", confirmationCode, ReservationStatus.CONFIRMED);
        r.totalAmount = totalAmount;
        r.adults = adults;
        r.children = children;
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
     * 노쇼 처리.
     *
     * <p><b>재고를 되돌리지 않는다.</b> 방은 비었지만 요금은 받으므로 {@code booked} 를
     * 그대로 둔다. 리포트의 매출 집계와도 이 편이 맞는다. 작업지시 02 의 5절 3번.
     */
    public void markNoShow() {
        if (status != ReservationStatus.CONFIRMED) {
            throw new IllegalReservationTransition(status, ReservationStatus.NO_SHOW);
        }
        this.status = ReservationStatus.NO_SHOW;
        touch();
    }

    /**
     * 숙박 기간과 인원을 바꾼다.
     *
     * <p>재고 이동은 이 메서드가 하지 않는다. 옛 기간을 되돌리고 새 기간을 잡는 일은
     * 락과 트랜잭션이 필요해 엔티티 바깥({@code BookingService})의 일이다. 여기서는
     * 바꿀 수 있는 상태인지만 판단하고 값을 갈아 끼운다.
     */
    public void changeStay(StayPeriod newPeriod, short newAdults, short newChildren) {
        if (!isActive()) {
            // 취소·만료·체크아웃된 예약의 날짜를 바꾸는 것은 의미가 없다.
            throw new IllegalReservationTransition(status, status);
        }
        this.period = newPeriod;
        this.adults = newAdults;
        this.children = newChildren;
        touch();
    }

    /**
     * 판매 단위를 옮긴다. 충돌 해소의 업그레이드 배정이다(계획서 7.4).
     *
     * <p>{@link #changeStay} 와 같은 자리다 — 재고 이동은 엔티티 바깥의 일이고
     * 여기서는 옮길 수 있는 상태인지만 보고 값을 갈아 끼운다.
     */
    public void moveToUnit(Long newUnitId) {
        if (!isActive()) {
            throw new IllegalReservationTransition(status, status);
        }
        this.unitId = newUnitId;
        touch();
    }

    /** 게스트를 연결한다. 수기 등록에서 게스트를 먼저 만든 뒤 부른다. */
    public void assignGuest(Long guestId) {
        this.guestId = guestId;
    }

    public Long getGuestId() {
        return guestId;
    }

    public short getAdults() {
        return adults;
    }

    public short getChildren() {
        return children;
    }

    public BigDecimal getChannelCommission() {
        return channelCommission;
    }

    /** 데이터베이스가 계산한 값. 저장 직후에는 비어 있을 수 있다. */
    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public OffsetDateTime getHoldExpiresAt() {
        return holdExpiresAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * OTA 에서 수정된 예약을 다시 받았을 때 반영한다.
     *
     * <p>전달 순서가 뒤바뀌어 이전 버전이 나중에 도착할 수 있으므로,
     * 더 높은 버전일 때만 적용한다.
     *
     * @return 실제로 반영했으면 true
     */
    /**
     * <b>버전이 없는 채널</b>에서 수정을 받았을 때 반영한다. iCal 이 이 경로다.
     *
     * <p>계획서 13.4 는 {@code hashOf(start, end, summary)} 를 버전 자리에 넣었지만,
     * {@link #applyRevision} 은 크기를 비교한다. <b>해시에는 순서가 없어서</b> 날짜가
     * 바뀐 뒤의 해시가 우연히 더 작으면 수정이 조용히 무시된다. 예약은 그대로 있고
     * 로그도 깨끗하며, 드러나는 것은 체크인 날 게스트가 다른 날짜를 들고 왔을 때다.
     *
     * <p>대신 값이 달라졌는지만 본다. 순서 역전은 이런 채널에서 일어나지 않는다 —
     * 매번 전체 스냅샷을 받기 때문이다. 근거는 ADR 0013.
     *
     * @return 실제로 달라져서 반영했으면 true
     */
    public boolean applyValues(StayPeriod newPeriod, BigDecimal newAmount) {
        boolean sameDates = period.checkIn().equals(newPeriod.checkIn())
                && period.checkOut().equals(newPeriod.checkOut());
        boolean sameAmount = totalAmount == null
                ? newAmount == null
                : newAmount != null && totalAmount.compareTo(newAmount) == 0;
        if (sameDates && sameAmount) {
            return false;
        }
        this.period = newPeriod;
        this.totalAmount = newAmount;
        touch();
        return true;
    }

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
