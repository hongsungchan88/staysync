package com.staysync.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.List;

/**
 * 숙박 기간. 체크아웃일은 포함하지 않는다(exclusive).
 * 12/25 입실 12/27 퇴실이면 숙박일은 12/25 와 12/26 두 밤이다.
 */
@Embeddable
public class StayPeriod {

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    protected StayPeriod() {
    }

    public StayPeriod(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null) {
            throw new IllegalArgumentException("체크인과 체크아웃 날짜는 필수입니다.");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("체크아웃은 체크인보다 뒤여야 합니다.");
        }
        this.checkIn = checkIn;
        this.checkOut = checkOut;
    }

    public LocalDate checkIn() {
        return checkIn;
    }

    public LocalDate checkOut() {
        return checkOut;
    }

    public int nights() {
        return (int) (checkOut.toEpochDay() - checkIn.toEpochDay());
    }

    /**
     * 재고를 차감해야 하는 날짜 목록. 반드시 오름차순이다.
     *
     * <p>락 획득 순서를 날짜 오름차순으로 고정하기 위해 정렬을 여기서 보장한다.
     * 스레드마다 순서가 다르면 교착 상태가 발생한다.
     */
    public List<LocalDate> nightDates() {
        return checkIn.datesUntil(checkOut).sorted().toList();
    }

    public boolean overlaps(StayPeriod other) {
        return checkIn.isBefore(other.checkOut) && other.checkIn.isBefore(checkOut);
    }
}
