package com.staysync.booking;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** 최소 숙박일보다 짧다. 요금 캘린더가 정한 값이다. */
public class MinimumStayException extends DomainException {

    public MinimumStayException(short required, int requested) {
        super("MIN_STAY_NOT_MET",
                "최소 %d박부터 예약할 수 있습니다. 요청은 %d박입니다.".formatted(required, requested));
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
