package com.staysync.booking;

import com.staysync.booking.domain.ReservationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BookingStatistics} 구현. 질의는 booking 의 저장소가 들고 있다.
 *
 * <p>상태 집합을 여기서 정한다. 리포트가 상태 이름을 알 필요가 없고, 알게 되면
 * 예약 상태가 늘 때 두 모듈을 함께 고쳐야 한다.
 */
@Service
@Transactional(readOnly = true)
class BookingStatisticsService implements BookingStatistics {

    /**
     * 팔린 것으로 세는 상태.
     *
     * <p><b>{@code HOLD} 는 빠진다.</b> 아직 팔린 것이 아니라 결제를 기다리는 점유이고
     * 15분 뒤 사라질 수 있다. 매출로 세면 리포트가 실제보다 커진다.
     *
     * <p><b>{@code NO_SHOW} 는 들어간다.</b> 방은 비었지만 요금은 받았다 — 매출이다.
     * 재고를 되돌리지 않는 것과 같은 이유다.
     */
    private static final List<ReservationStatus> SOLD = List.of(
            ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN,
            ReservationStatus.CHECKED_OUT, ReservationStatus.NO_SHOW);

    /**
     * 취소율의 분모에서 빼는 상태.
     *
     * <p>{@code EXPIRED} 는 결제하지 않아 사라진 홀드다. <b>손님이 취소한 것이
     * 아니다.</b> 세면 직접예약 위젯을 붙인 뒤 취소율이 뛴다.
     */
    private static final List<ReservationStatus> NOT_COUNTED = List.of(
            ReservationStatus.HOLD, ReservationStatus.EXPIRED);

    /** 네이티브 질의에는 enum 이 아니라 이름을 넘긴다. */
    private static List<String> names(List<ReservationStatus> statuses) {
        return statuses.stream().map(Enum::name).toList();
    }

    private final ReservationRepository reservations;
    private final ReservationNightRepository nights;

    BookingStatisticsService(ReservationRepository reservations,
                             ReservationNightRepository nights) {
        this.reservations = reservations;
        this.nights = nights;
    }

    @Override
    public SoldNights soldNights(List<Long> propertyIds, LocalDate from, LocalDate to) {
        if (propertyIds.isEmpty()) {
            return new SoldNights(0, BigDecimal.ZERO);
        }
        return nights.aggregateSold(propertyIds, SOLD, from, to);
    }

    @Override
    public BigDecimal averageLeadTimeDays(List<Long> propertyIds, LocalDate from, LocalDate to) {
        if (propertyIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Double avg = reservations.averageLeadTimeDays(propertyIds, names(SOLD), from, to);
        return avg == null ? BigDecimal.ZERO : BigDecimal.valueOf(avg);
    }

    @Override
    public CancellationCounts cancellationCounts(List<Long> propertyIds,
                                                 LocalDate from, LocalDate to) {
        if (propertyIds.isEmpty()) {
            return new CancellationCounts(0, 0);
        }
        long total = reservations.countBooked(propertyIds, NOT_COUNTED, from, to);
        long cancelled = reservations.countCancelled(propertyIds, from, to);
        return new CancellationCounts(cancelled, total);
    }

    @Override
    public List<ChannelVolume> channelVolumes(List<Long> propertyIds,
                                              LocalDate from, LocalDate to) {
        if (propertyIds.isEmpty()) {
            return List.of();
        }
        return nights.aggregateByChannel(propertyIds, SOLD, from, to);
    }
}
