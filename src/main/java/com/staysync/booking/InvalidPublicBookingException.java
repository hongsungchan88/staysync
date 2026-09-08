package com.staysync.booking;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** 위젯이 보낸 값이 올바르지 않다. */
public class InvalidPublicBookingException extends DomainException {

    public InvalidPublicBookingException(String message) {
        super("INVALID_PUBLIC_BOOKING", message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
