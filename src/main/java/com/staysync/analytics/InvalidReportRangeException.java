package com.staysync.analytics;

import com.staysync.shared.error.DomainException;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;

/** 리포트 기간이 올바르지 않다. */
public class InvalidReportRangeException extends DomainException {

    public InvalidReportRangeException(LocalDate from, LocalDate to) {
        super("INVALID_REPORT_RANGE",
                "기간이 올바르지 않습니다. from=%s to=%s".formatted(from, to));
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
