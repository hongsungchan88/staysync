package com.staysync.payment;

import com.staysync.shared.error.DomainException;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

/**
 * 결제사에서 재조회한 금액이 예약 금액과 다르다.
 *
 * <p><b>확정하지 않는다.</b> 여기서 넘어가면 결제 금액과 예약 금액이 다른 예약이
 * 확정되고, <b>화면에는 아무 이상이 없다.</b> 작업지시 13 이 "돈이 걸린 자리에서
 * 화면이 정상으로 보이는 결함"이라고 적어 둔 자리가 이것이다.
 *
 * <p>금액을 로그에는 남기되 응답 본문에는 담지 않는다 — 응답을 받는 것은 손님이
 * 아니라 포트원이고, 우리 예약 금액을 알려 줄 이유가 없다.
 */
public class PaymentAmountMismatchException extends DomainException {

    public PaymentAmountMismatchException(BigDecimal expected, BigDecimal actual) {
        super("PAYMENT_AMOUNT_MISMATCH", "결제 금액이 예약 금액과 다릅니다.");
        this.expected = expected;
        this.actual = actual;
    }

    private final BigDecimal expected;
    private final BigDecimal actual;

    public BigDecimal expected() {
        return expected;
    }

    public BigDecimal actual() {
        return actual;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
