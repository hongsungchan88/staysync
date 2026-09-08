package com.staysync.payment;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 결제사에 결제를 다시 물어보지 못했다.
 *
 * <p><b>모르면 확정하지 않는다.</b> 못 물어본 것과 금액이 맞는 것은 다르다. 여기서
 * 낙관적으로 넘어가면 결제사 장애 중에 들어온 위조 웹훅이 그대로 예약을 확정시킨다.
 *
 * <p>502 다. 포트원이 이 응답을 받으면 재시도하고, 그 사이 우리가 복구되면 확정된다.
 * 4xx 로 답하면 포트원이 다시 보내지 않아 결제는 됐는데 예약이 홀드로 남는다.
 */
public class PaymentLookupException extends DomainException {

    public PaymentLookupException(String paymentId, Throwable cause) {
        super("PAYMENT_LOOKUP_FAILED", "결제 정보를 조회하지 못했습니다. paymentId=%s".formatted(paymentId));
        initCause(cause);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_GATEWAY;
    }
}
