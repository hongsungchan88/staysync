package com.staysync.booking.calendar;

import com.staysync.shared.error.DomainException;

/**
 * 캘린더 조회 기간이 올바르지 않다.
 *
 * <p>상한을 두는 이유는 성능이 아니라 방어다. 상한이 없으면 한 번의 요청으로 원장
 * 전체를 긁을 수 있다.
 */
public class InvalidCalendarRangeException extends DomainException {

    public InvalidCalendarRangeException(String message) {
        super("INVALID_CALENDAR_RANGE", message);
    }
}
