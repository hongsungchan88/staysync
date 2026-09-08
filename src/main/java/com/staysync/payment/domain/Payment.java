package com.staysync.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 결제 한 건. V1 의 {@code payment} 테이블이다. P4 16주차에 처음 쓴다.
 *
 * <h2>카드 정보를 담지 않는다</h2>
 *
 * <p>남는 것은 <b>결제사 식별자와 금액과 상태</b>뿐이다. 카드 번호도 명의도 우리
 * 서버를 지나가지 않는다 — 결제창이 브라우저에서 결제사와 직접 이야기하고, 우리는
 * 그 결과만 받는다.
 *
 * <h2>{@code providerTxId} 가 멱등성의 열쇠다</h2>
 *
 * <p>{@code uq_payment_tx UNIQUE (provider, provider_tx_id)} 가 걸려 있다. 포트원
 * 웹훅은 최소 1회 전달이라 <b>같은 결제 완료 웹훅이 두 번 오는 것이 정상</b>이고,
 * 그때 결제 행이 둘이 되면 매출이 두 배로 잡힌다.
 *
 * <p>여기 담기는 값은 우리가 만든 결제 식별자다(포트원의 {@code paymentId}).
 * 결제창을 열기 전에 <b>PENDING 으로 먼저 만들어 둔다</b> — 웹훅이 도착했을 때 그
 * 식별자가 어느 예약의 것인지 알 방법이 이것뿐이다. 웹훅 본문의 예약 식별자를 믿으면
 * 아무나 남의 예약을 확정시킬 수 있다.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(nullable = false, length = 30)
    private String provider;

    /** 포트원의 {@code paymentId}. 우리가 만들어 결제창에 넘긴 값이다. */
    @Column(name = "provider_tx_id", length = 120)
    private String providerTxId;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Payment() {
    }

    /**
     * 결제창을 열기 전에 만드는 행.
     *
     * <p>금액은 <b>예약에서 가져온 값</b>이다. 화면이 보낸 값을 쓰지 않는다 — 위젯의
     * 홀드에서 이미 한 번 대조했고, 여기서 다시 화면을 믿으면 그 대조가 무의미해진다.
     */
    public static Payment pending(Long reservationId, String paymentId, BigDecimal amount) {
        Payment p = new Payment();
        p.reservationId = reservationId;
        p.provider = "PORTONE";
        p.providerTxId = paymentId;
        p.kind = "CHARGE";
        p.amount = amount;
        p.status = PaymentStatus.PENDING;
        return p;
    }

    /**
     * 결제사 재조회까지 맞은 결제.
     *
     * <p><b>멱등하다.</b> 이미 PAID 면 결제 시각을 다시 쓰지 않는다 — 웹훅 재전송이
     * 정상 동작이고, 다시 쓰면 실제 결제 시각이 재전송 시각으로 밀린다.
     */
    public void markPaid(OffsetDateTime at) {
        if (status == PaymentStatus.PAID) {
            return;
        }
        this.status = PaymentStatus.PAID;
        this.paidAt = at;
    }

    /** 대조가 어긋났거나 결제사가 실패로 알렸다. 예약은 확정하지 않는다. */
    public void markFailed() {
        // 이미 결제된 건을 실패로 되돌리지 않는다. 취소는 환불이고 이번 주가 아니다.
        if (status == PaymentStatus.PAID) {
            return;
        }
        this.status = PaymentStatus.FAILED;
    }

    public Long getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public String getProviderTxId() {
        return providerTxId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
