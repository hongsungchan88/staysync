package com.staysync.booking;

import com.staysync.booking.domain.Reservation;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 HOLD 를 EXPIRED 로 바꾸고 재고를 되돌린다.
 *
 * <p>HOLD 수명은 15분이다(계획서 5.4). 결제를 끝내지 못한 임시 점유를 계속 두면 팔 수
 * 있는 방이 팔리지 않는다.
 *
 * <p><b>배치도 {@link BookingService} 를 거친다.</b> 원장을 직접 고치면 락과 방어 계층을
 * 우회하게 되고, 그 순간 사용자 요청과 배치가 같은 재고를 동시에 건드릴 수 있다.
 *
 * <p>한 번에 처리할 건수에 상한을 둔다. 밀린 HOLD 가 많을 때 한 트랜잭션이 길어지면
 * 락을 오래 쥐어 그 판매 단위의 예약이 전부 막힌다. 남은 것은 다음 주기에 처리한다.
 *
 * <p>인스턴스가 하나뿐이라 ShedLock 은 아직 걸지 않는다. 리프레시 토큰 정리 배치와 같다.
 */
@Component
public class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    /** 한 주기에 처리할 최대 건수. */
    static final int BATCH_LIMIT = 100;

    private final ReservationRepository reservationRepo;
    private final BookingService bookingService;

    HoldExpiryJob(ReservationRepository reservationRepo, BookingService bookingService) {
        this.reservationRepo = reservationRepo;
        this.bookingService = bookingService;
    }

    /** 1분마다. HOLD 수명이 15분이라 이 정도면 만료가 오래 방치되지 않는다. */
    @Scheduled(fixedDelay = 60_000)
    public void run() {
        expireDueHolds(OffsetDateTime.now());
    }

    /**
     * 스케줄러를 기다리지 않고 부를 수 있게 분리했다. 테스트가 이 메서드를 쓴다.
     *
     * @return 만료시킨 건수
     */
    public int expireDueHolds(OffsetDateTime now) {
        List<Reservation> due = reservationRepo.findExpiredHolds(now, PageRequest.of(0, BATCH_LIMIT));

        int expired = 0;
        for (Reservation reservation : due) {
            try {
                // 건마다 따로 처리한다. 하나가 실패해도 나머지는 만료돼야 한다.
                bookingService.expireHold(reservation.getId());
                expired++;
            } catch (RuntimeException e) {
                log.warn("HOLD 만료 처리에 실패했다. 다음 주기에 다시 시도한다. id={}",
                        reservation.getId(), e);
            }
        }
        if (expired > 0) {
            log.info("만료된 HOLD {}건을 정리했다", expired);
        }
        if (due.size() == BATCH_LIMIT) {
            // 상한에 닿았다는 것은 처리하지 못하고 남은 HOLD 가 더 있을 수 있다는 뜻이다.
            // 남은 것은 다음 주기에 처리되지만, 매 주기 상한에 닿는다면 유입이 처리 속도를
            // 앞지르고 있다는 신호다. 그 상태로 두면 팔 수 있는 방이 계속 묶인다.
            // 조용히 잘리면 밀린 HOLD 가 쌓여도 정상으로 보이므로 반드시 남긴다.
            log.warn("HOLD 만료 처리가 한 주기 상한 {}건에 닿았다. 남은 건은 다음 주기로 넘긴다. "
                    + "이 로그가 계속 나오면 상한이나 주기를 조정해야 한다", BATCH_LIMIT);
        }
        return expired;
    }
}
