package com.staysync.payment;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 결제할 수 있는 상태가 아니다.
 *
 * <p>홀드가 아닌 예약에 결제창을 열려 한 것이다. 이미 확정됐거나, 15분이 지나 만료됐거나,
 * 취소된 예약이다. <b>만료가 가장 흔하다</b> — 손님이 결제창을 열어 두고 자리를 비운
 * 경우이고, 그때 "예약이 만료되었습니다"가 뜨는 것이 맞다.
 */
public class NotPayableException extends DomainException {

    public NotPayableException(String status) {
        super("NOT_PAYABLE", switch (status) {
            case "EXPIRED" -> "예약 대기 시간이 지났습니다. 처음부터 다시 예약해 주세요.";
            case "CONFIRMED" -> "이미 결제가 끝난 예약입니다.";
            case "CANCELLED" -> "취소된 예약입니다.";
            default -> "지금은 결제할 수 없는 예약입니다.";
        });
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
