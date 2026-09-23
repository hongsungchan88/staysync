package com.staysync.booking.domain;

import com.staysync.shared.error.DomainException;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;

/** 퇴실일이 지나 체크아웃을 되돌릴 수 없다. */
public class CheckOutUndoExpiredException extends DomainException {

    public CheckOutUndoExpiredException(LocalDate checkOut) {
        super("CHECK_OUT_UNDO_EXPIRED",
                "퇴실일(%s)이 지나 체크아웃을 되돌릴 수 없습니다.".formatted(checkOut));
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
