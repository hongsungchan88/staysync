package com.staysync.pricing.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** {@link RateCalendar} 의 복합 키. */
public class RateCalendarId implements Serializable {

    private Long ratePlanId;
    private LocalDate stayDate;

    protected RateCalendarId() {
    }

    public RateCalendarId(Long ratePlanId, LocalDate stayDate) {
        this.ratePlanId = ratePlanId;
        this.stayDate = stayDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof RateCalendarId other
                && Objects.equals(ratePlanId, other.ratePlanId)
                && Objects.equals(stayDate, other.stayDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ratePlanId, stayDate);
    }
}
