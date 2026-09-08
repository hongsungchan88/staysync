package com.staysync.booking;

import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.ReservationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link ReservationPaymentGate} 구현.
 *
 * <p><b>락은 {@link BookingService} 가 잡는다.</b> 여기서 직접 잡지 않는다 — 재고
 * 승격이 락 안에서 일어나야 하고 그 경계는 이미 저기에 있다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다. 락을 잡는 쪽을 부르므로
 * 트랜잭션이 락보다 먼저 열리면 안 된다 — 열린 채 락을 기다리면 커넥션 풀이 마른다.
 * 13주차 {@code ConflictResolutionService} 에서 같은 함정에 빠졌다.
 */
@Service
class ReservationPaymentGateService implements ReservationPaymentGate {

    private static final Logger log = LoggerFactory.getLogger(ReservationPaymentGateService.class);

    private final BookingService booking;
    private final ReservationRepository reservations;

    ReservationPaymentGateService(BookingService booking, ReservationRepository reservations) {
        this.booking = booking;
        this.reservations = reservations;
    }

    @Override
    public boolean confirmPaid(Long reservationId) {
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        // **먼저 조회해 갈린다.** 이미 확정이면 재고를 다시 승격하지 않는다 — 두 번
        // 승격하면 원장이 틀어져 있지도 않은 재고를 팔게 된다. 웹훅 재전송은 정상
        // 동작이므로 여기서 예외를 던지면 포트원이 계속 재시도한다.
        if (reservation.getStatus() != ReservationStatus.HOLD) {
            log.info("이미 홀드가 아닌 예약에 결제 확정이 들어왔다. 그대로 둔다. "
                    + "reservationId={} status={}", reservationId, reservation.getStatus());
            return false;
        }

        booking.confirm(reservationId);
        log.info("결제로 예약을 확정했다. reservationId={}", reservationId);
        return true;
    }
}
