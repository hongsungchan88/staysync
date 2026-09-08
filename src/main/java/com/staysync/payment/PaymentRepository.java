package com.staysync.payment;

import com.staysync.payment.domain.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * 결제 식별자로 찾는다. <b>웹훅이 예약에 닿는 유일한 경로다.</b>
     *
     * <p>웹훅 본문에는 우리 예약 식별자가 없다. 있더라도 믿지 않는다 — 서명이 맞아도
     * 그 안의 값이 어느 예약을 가리키는지는 우리가 정한 것이어야 한다.
     */
    Optional<Payment> findByProviderAndProviderTxId(String provider, String providerTxId);
}
