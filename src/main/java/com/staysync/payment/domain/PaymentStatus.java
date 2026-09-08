package com.staysync.payment.domain;

/**
 * 결제 상태. V1 의 {@code payment.status} 주석과 같은 값이다.
 *
 * <p>{@code REFUNDED} 는 자리만 있고 이번 주에 만들지 않는다 — 환불은 작업지시 13 의
 * 3절이 "이번 주가 아니다"로 미뤄 둔 항목이다. 값을 지우지 않는 이유는 스키마의
 * 주석이 이미 넷을 적어 두었기 때문이다.
 */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED
}
