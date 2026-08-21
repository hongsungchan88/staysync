package com.staysync.shared.lock;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** 제한 시간 안에 재고 락을 얻지 못했을 때 발생한다. */
public class LockAcquisitionException extends DomainException {

    public LockAcquisitionException(String message) {
        super("LOCK_TIMEOUT", message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
