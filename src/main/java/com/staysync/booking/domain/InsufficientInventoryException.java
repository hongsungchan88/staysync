package com.staysync.booking.domain;

import com.staysync.shared.error.DomainException;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;

/** 요청한 날짜에 판매 가능한 재고가 없을 때 발생한다. */
public class InsufficientInventoryException extends DomainException {

    private final Long unitId;
    private final LocalDate date;
    private final String reason;

    public InsufficientInventoryException(Long unitId, LocalDate date, String reason) {
        super("INVENTORY_" + reason,
                "%s 에 판매 가능한 재고가 없습니다. (사유: %s)".formatted(date, reason));
        this.unitId = unitId;
        this.date = date;
        this.reason = reason;
    }

    public Long unitId() {
        return unitId;
    }

    public LocalDate date() {
        return date;
    }

    public String reason() {
        return reason;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
