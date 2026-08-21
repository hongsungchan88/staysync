package com.staysync.booking.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 일자별 재고 원장. 판매 단위 하나와 날짜 하나가 한 행이다.
 *
 * <p>중복예약 방지의 기준 테이블이며, 데이터베이스에
 * {@code CHECK (booked_units + held_units <= total_units)} 제약이 걸려 있다.
 * 애플리케이션 로직에 결함이 있어도 이 제약이 초과 판매를 최종적으로 거부한다.
 */
@Entity
@Table(name = "inventory_ledger")
@IdClass(InventoryLedgerId.class)
public class InventoryLedger {

    @Id
    @Column(name = "unit_id")
    private Long unitId;

    @Id
    @Column(name = "stay_date")
    private LocalDate stayDate;

    @Column(name = "total_units", nullable = false)
    private short totalUnits;

    @Column(name = "booked_units", nullable = false)
    private short bookedUnits;

    @Column(name = "held_units", nullable = false)
    private short heldUnits;

    @Column(name = "stop_sell", nullable = false)
    private boolean stopSell;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected InventoryLedger() {
    }

    public InventoryLedger(Long unitId, LocalDate stayDate, short totalUnits) {
        this.unitId = unitId;
        this.stayDate = stayDate;
        this.totalUnits = totalUnits;
        this.bookedUnits = 0;
        this.heldUnits = 0;
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getUnitId() {
        return unitId;
    }

    public LocalDate getStayDate() {
        return stayDate;
    }

    public short getTotalUnits() {
        return totalUnits;
    }

    public short getBookedUnits() {
        return bookedUnits;
    }

    public short getHeldUnits() {
        return heldUnits;
    }

    public boolean isStopSell() {
        return stopSell;
    }

    /** 지금 팔 수 있는 수량. */
    public int available() {
        return totalUnits - bookedUnits - heldUnits;
    }

    /**
     * 채널에 노출할 수량. 지연이 큰 채널에는 일부를 유보해 중복예약 확률을 낮춘다.
     *
     * @param buffer 유보할 수량. iCal 채널은 보통 1
     */
    public int exposedTo(int buffer) {
        return Math.max(0, available() - buffer);
    }

    /** 확정 예약으로 재고를 차감한다. */
    public void book(int units) {
        ensureAvailable(units);
        this.bookedUnits += (short) units;
        touch();
    }

    /** 결제 대기 상태로 임시 점유한다. */
    public void hold(int units) {
        ensureAvailable(units);
        this.heldUnits += (short) units;
        touch();
    }

    /** 임시 점유를 확정 예약으로 승격한다. */
    public void promoteHold(int units) {
        if (heldUnits < units) {
            throw new IllegalStateException("승격할 임시 점유 수량이 부족합니다. " + describe());
        }
        this.heldUnits -= (short) units;
        this.bookedUnits += (short) units;
        touch();
    }

    /** 임시 점유를 해제한다. 결제 실패나 시간 만료 시 호출한다. */
    public void releaseHold(int units) {
        this.heldUnits = (short) Math.max(0, heldUnits - units);
        touch();
    }

    /** 확정 예약을 취소해 재고를 되돌린다. */
    public void release(int units) {
        this.bookedUnits = (short) Math.max(0, bookedUnits - units);
        touch();
    }

    /**
     * OTA 에서 이미 성사된 예약을 받아들이기 위해 재고 한도를 넘겨 기록한다.
     *
     * <p>iCal 지연 구간에서 발생한 예약은 우리가 거절할 수 없다. 거절하면 게스트와
     * 플랫폼 양쪽에서 문제가 된다. 대신 총 수량을 함께 늘려 데이터베이스 제약을
     * 만족시키고, 별도로 충돌 레코드를 남겨 운영자가 해소하게 한다.
     */
    public void forceBook(int units) {
        this.bookedUnits += (short) units;
        if (bookedUnits + heldUnits > totalUnits) {
            this.totalUnits = (short) (bookedUnits + heldUnits);
        }
        touch();
    }

    public void changeTotalUnits(short newTotal) {
        if (newTotal < bookedUnits + heldUnits) {
            throw new IllegalStateException(
                    "이미 판매된 수량보다 적게 줄일 수 없습니다. " + describe());
        }
        this.totalUnits = newTotal;
        touch();
    }

    public void changeStopSell(boolean stopSell) {
        this.stopSell = stopSell;
        touch();
    }

    private void ensureAvailable(int units) {
        if (stopSell) {
            throw new InsufficientInventoryException(unitId, stayDate, "STOP_SELL");
        }
        if (available() < units) {
            throw new InsufficientInventoryException(unitId, stayDate, "SOLD_OUT");
        }
    }

    private void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    private String describe() {
        return "unitId=%d date=%s total=%d booked=%d held=%d"
                .formatted(unitId, stayDate, totalUnits, bookedUnits, heldUnits);
    }
}
