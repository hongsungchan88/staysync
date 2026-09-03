package com.staysync.booking.calendar;

import com.staysync.shared.error.DomainException;

/** 일괄 편집 요청이 규칙에 맞지 않을 때. */
public class InvalidBulkEditException extends DomainException {

    public InvalidBulkEditException(String message) {
        super("INVALID_BULK_EDIT", message);
    }
}
