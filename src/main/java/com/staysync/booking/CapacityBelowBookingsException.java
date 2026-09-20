package com.staysync.booking;

import com.staysync.shared.error.DomainException;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 판매 수량을 이미 팔린 날 아래로 줄이려 했다(작업지시-19 5절 4번).
 *
 * <p>막는 날짜를 {@code details} 에 담는다 — 사람이 어느 예약을 옮기거나 취소해야 줄일 수
 * 있는지 알아야 다음 행동을 고른다. 초과 상태로 만들어 주지 않는다: 초과는 채널이 밀어
 * 넣을 때만 받는 것({@code forceBook})이지 호스트가 스스로 만드는 자리가 아니다.
 */
public class CapacityBelowBookingsException extends DomainException {

    private final List<LocalDate> blockingDates;

    public CapacityBelowBookingsException(Long unitId, short requested, List<LocalDate> blockingDates) {
        super("CAPACITY_BELOW_BOOKINGS",
                "이미 팔린 날이 있어 수량을 %d 으로 줄일 수 없습니다. 막는 날짜 %d 일 — 첫 날 %s"
                        .formatted(requested, blockingDates.size(), blockingDates.get(0)));
        this.blockingDates = List.copyOf(blockingDates);
    }

    public List<LocalDate> blockingDates() {
        return blockingDates;
    }

    @Override
    public List<String> details() {
        return blockingDates.stream().map(LocalDate::toString).toList();
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
