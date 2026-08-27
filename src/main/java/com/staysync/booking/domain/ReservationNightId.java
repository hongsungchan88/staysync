package com.staysync.booking.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** {@link ReservationNight} 의 복합 키. */
public class ReservationNightId implements Serializable {

    private Long reservationId;
    private LocalDate stayDate;

    protected ReservationNightId() {
    }

    public ReservationNightId(Long reservationId, LocalDate stayDate) {
        this.reservationId = reservationId;
        this.stayDate = stayDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof ReservationNightId other
                && Objects.equals(reservationId, other.reservationId)
                && Objects.equals(stayDate, other.stayDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reservationId, stayDate);
    }
}
