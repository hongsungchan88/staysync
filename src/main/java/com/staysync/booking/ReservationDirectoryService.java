package com.staysync.booking;

import com.staysync.booking.domain.Guest;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.ReservationStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReservationDirectory} 의 구현.
 *
 * <p>게스트 이름을 한 번에 읽는다. 예약마다 조회하면 목록 길이만큼 왕복이 늘고,
 * 그건 캘린더 조립부에서 이미 겪은 모양이다({@code CalendarService.guestNames}).
 */
@Service
@Transactional(readOnly = true)
class ReservationDirectoryService implements ReservationDirectory {

    /** 재고를 쥐고 있는 상태. 자동 발송 대상은 이 셋뿐이다. */
    private static final List<ReservationStatus> ACTIVE = List.of(
            ReservationStatus.HOLD, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN);

    private final ReservationRepository reservations;
    private final GuestRepository guests;

    ReservationDirectoryService(ReservationRepository reservations, GuestRepository guests) {
        this.reservations = reservations;
        this.guests = guests;
    }

    @Override
    public Optional<ReservationBrief> find(Long reservationId) {
        return reservations.findById(reservationId).map(this::toBrief);
    }

    @Override
    public Optional<ReservationBrief> findByChannel(String channelCode, String channelBookingId) {
        return reservations.findByChannelCodeAndChannelBookingId(channelCode, channelBookingId)
                .map(this::toBrief);
    }

    @Override
    public List<ReservationBrief> findAll(Collection<Long> reservationIds) {
        return toBriefs(reservations.findAllById(reservationIds));
    }

    @Override
    public List<ReservationBrief> activeByCheckIn(LocalDate date) {
        return toBriefs(reservations.findActiveByCheckIn(date, ACTIVE));
    }

    @Override
    public List<ReservationBrief> activeByCheckOut(LocalDate date) {
        return toBriefs(reservations.findActiveByCheckOut(date, ACTIVE));
    }

    private List<ReservationBrief> toBriefs(List<Reservation> found) {
        Map<Long, String> names = guestNames(found);
        return found.stream().map(r -> toBrief(r, names)).toList();
    }

    private ReservationBrief toBrief(Reservation reservation) {
        return toBrief(reservation, guestNames(List.of(reservation)));
    }

    private static ReservationBrief toBrief(Reservation r, Map<Long, String> names) {
        return new ReservationBrief(
                r.getId(), r.getPropertyId(), r.getUnitId(), r.getGuestId(),
                r.getConfirmationCode(), r.getChannelCode(), r.getChannelBookingId(),
                r.getStatus().name(),
                r.getPeriod().checkIn(), r.getPeriod().checkOut(),
                r.getGuestId() == null ? null : names.get(r.getGuestId()));
    }

    /** 이름만 읽는다. 연락처는 암호문이고 복호화하는 자리는 {@code GuestRegistrar} 다. */
    private Map<Long, String> guestNames(List<Reservation> found) {
        List<Long> guestIds = found.stream()
                .map(Reservation::getGuestId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (guestIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Guest guest : guests.findAllById(guestIds)) {
            names.put(guest.getId(), guest.getName());
        }
        return names;
    }
}
