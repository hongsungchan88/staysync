package com.staysync.booking.domain;

import com.staysync.shared.error.DomainException;

/** 허용되지 않는 예약 상태 전이를 시도했을 때 발생한다. */
public class IllegalReservationTransition extends DomainException {

    public IllegalReservationTransition(ReservationStatus from, ReservationStatus to) {
        super("ILLEGAL_RESERVATION_TRANSITION",
                "%s 상태에서 %s 로 바꿀 수 없습니다.".formatted(from, to));
    }
}
