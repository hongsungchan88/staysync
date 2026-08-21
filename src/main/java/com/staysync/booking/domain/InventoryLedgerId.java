package com.staysync.booking.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/** {@link InventoryLedger} 의 복합 기본키. */
public class InventoryLedgerId implements Serializable {

    private Long unitId;
    private LocalDate stayDate;

    public InventoryLedgerId() {
    }

    public InventoryLedgerId(Long unitId, LocalDate stayDate) {
        this.unitId = unitId;
        this.stayDate = stayDate;
    }

    public Long getUnitId() {
        return unitId;
    }

    public LocalDate getStayDate() {
        return stayDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InventoryLedgerId other)) {
            return false;
        }
        return Objects.equals(unitId, other.unitId) && Objects.equals(stayDate, other.stayDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(unitId, stayDate);
    }
}
