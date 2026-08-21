package com.staysync.booking.domain;

/** 예약 상태. 전이 규칙은 Reservation 안에 있다. */
public enum ReservationStatus {
    /** 직접예약에서 결제를 기다리는 임시 점유 상태. 기본 15분 후 만료된다. */
    HOLD,
    CONFIRMED,
    CANCELLED,
    CHECKED_IN,
    CHECKED_OUT,
    NO_SHOW,
    EXPIRED
}
