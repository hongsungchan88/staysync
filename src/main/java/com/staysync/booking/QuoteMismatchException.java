package com.staysync.booking;

import com.staysync.shared.error.DomainException;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

/**
 * 화면이 보낸 금액과 서버 재계산이 다르다.
 *
 * <p><b>서버 값을 조용히 쓰지 않는다.</b> 화면에 뜬 값으로 결제창이 열리므로, 어긋난 채
 * 진행하면 결제 금액과 예약 금액이 다른 예약이 생긴다. 사용자에게 다시 확인시킨다.
 *
 * <p>메시지에 서버 값을 담는다. 요금은 게스트에게 보여 주는 값이라 숨길 것이 아니고,
 * 화면이 새 값으로 다시 그려야 한다.
 */
public class QuoteMismatchException extends DomainException {

    public QuoteMismatchException(BigDecimal quoted, BigDecimal actual) {
        super("QUOTE_MISMATCH",
                "요금이 변경되었습니다. 확인한 금액 %s, 현재 금액 %s".formatted(quoted, actual));
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
