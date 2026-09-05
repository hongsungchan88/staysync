package com.staysync.booking;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** 해소 요청에 필요한 값이 빠졌거나 모르는 방법이다. */
public class InvalidConflictResolutionException extends DomainException {

    public InvalidConflictResolutionException(String message) {
        super("INVALID_CONFLICT_RESOLUTION", message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
