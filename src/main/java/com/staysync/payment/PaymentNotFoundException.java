package com.staysync.payment;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** 그 결제 식별자로 준비된 결제가 없다. 우리가 시작하지 않은 결제다. */
public class PaymentNotFoundException extends DomainException {

    public PaymentNotFoundException(String paymentId) {
        super("PAYMENT_NOT_FOUND", "결제 정보를 찾을 수 없습니다. paymentId=%s".formatted(paymentId));
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
