package com.staysync.booking;

import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.shared.lock.UnitLock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 예약 생애주기의 진입점. <b>락 경계</b>다.
 *
 * <p>이 클래스는 락만 잡고 실제 쓰기는 {@link ReservationWriter} 에 맡긴다. 둘을 나눈
 * 이유는 두 가지다.
 *
 * <ol>
 *   <li>락을 먼저 잡고 그 안에서 트랜잭션을 열어야 한다. 순서가 반대면 트랜잭션이 열린 채
 *       락을 기다려 커넥션 풀이 마른다. {@code InventoryService} 와
 *       {@code InventoryLedgerWriter} 를 나눈 것과 같은 이유다</li>
 *   <li>같은 클래스 안에서 {@code @Transactional} 메서드를 부르면 스프링 프록시를 거치지
 *       않아 트랜잭션이 아예 걸리지 않는다</li>
 * </ol>
 *
 * <p>어떤 판매 단위를 잠글지 알려면 예약을 먼저 읽어야 한다. 그 읽기는 락 밖에서 한다.
 * 읽은 뒤 상태가 바뀔 수 있지만, 실제 판단은 락 안에서 다시 읽어 하므로 문제가 없다.
 * 여기서 필요한 것은 {@code unitId} 뿐이고 그건 예약의 생애 동안 바뀌지 않는다.
 */
@Service
public class BookingService {

    private static final Duration LOCK_WAIT = Duration.ofSeconds(3);

    private final UnitLock unitLock;
    private final ReservationWriter writer;
    private final ReservationRepository reservationRepo;
    private final ConfirmationCodeGenerator codeGenerator;
    private final int holdExpiryMinutes;

    BookingService(UnitLock unitLock,
                   ReservationWriter writer,
                   ReservationRepository reservationRepo,
                   ConfirmationCodeGenerator codeGenerator,
                   @Value("${staysync.hold-expiry-minutes:15}") int holdExpiryMinutes) {
        this.unitLock = unitLock;
        this.writer = writer;
        this.reservationRepo = reservationRepo;
        this.codeGenerator = codeGenerator;
        this.holdExpiryMinutes = holdExpiryMinutes;
    }

    /**
     * 수기 예약을 등록한다.
     *
     * <p>재고가 모자라면 <b>거절한다.</b> 채널 수신과 다르다. CLAUDE.md 가 거절하지 말라고
     * 한 것은 이미 외부에서 성사된 예약이고, 수기 등록은 아직 성사되지 않았으므로 막는
     * 것이 맞다.
     */
    public Reservation registerManual(Long propertyId, Long unitId, StayPeriod period,
                                      BigDecimal totalAmount, short adults, short children,
                                      Long guestId) {
        return unitLock.runExclusively(unitId, LOCK_WAIT, () ->
                writer.createConfirmed(propertyId, unitId, period, uniqueCode(),
                        totalAmount, adults, children, guestId));
    }

    /** 임시 점유를 만든다. 직접예약 위젯(P5)이 쓸 경로이며 지금은 서비스로만 열려 있다. */
    public Reservation hold(Long propertyId, Long unitId, StayPeriod period,
                            BigDecimal totalAmount, Long guestId) {
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(holdExpiryMinutes);
        return unitLock.runExclusively(unitId, LOCK_WAIT, () ->
                writer.createHold(propertyId, unitId, period, uniqueCode(),
                        totalAmount, expiresAt, guestId));
    }

    public Reservation confirm(Long reservationId) {
        return withUnitLock(reservationId, () -> writer.confirm(reservationId));
    }

    public Reservation cancel(Long reservationId) {
        return withUnitLock(reservationId, () -> writer.cancel(reservationId));
    }

    public Reservation checkIn(Long reservationId) {
        return withUnitLock(reservationId, () -> writer.checkIn(reservationId));
    }

    public Reservation checkOut(Long reservationId) {
        return withUnitLock(reservationId, () -> writer.checkOut(reservationId));
    }

    public Reservation markNoShow(Long reservationId) {
        return withUnitLock(reservationId, () -> writer.markNoShow(reservationId));
    }

    public Reservation changeStay(Long reservationId, StayPeriod newPeriod,
                                  short adults, short children) {
        return withUnitLock(reservationId,
                () -> writer.changeStay(reservationId, newPeriod, adults, children));
    }

    /** 만료 배치가 부른다. 배치도 반드시 락을 거쳐야 방어 계층을 우회하지 않는다. */
    public void expireHold(Long reservationId) {
        withUnitLock(reservationId, () -> {
            writer.expire(reservationId);
            return null;
        });
    }

    private <T> T withUnitLock(Long reservationId, java.util.function.Supplier<T> action) {
        Long unitId = unitIdOf(reservationId);
        return unitLock.runExclusively(unitId, LOCK_WAIT, action);
    }

    /**
     * 잠글 대상을 알기 위한 읽기. 락 밖에서 한다.
     *
     * <p>{@code @Transactional} 을 붙이지 않는다. 같은 클래스 안에서 부르는 메서드라
     * 프록시를 거치지 않아 어차피 걸리지 않고, 리포지토리 조회 한 번이라 스프링 데이터가
     * 여는 트랜잭션으로 충분하다.
     */
    private Long unitIdOf(Long reservationId) {
        return reservationRepo.findById(reservationId)
                .map(Reservation::getUnitId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    /**
     * 충돌하지 않는 확인 코드를 뽑는다.
     *
     * <p>{@code uq_confirmation} 이 최종 방어선이지만, 제약 위반 예외를 그대로 올리면
     * 사용자에게 원인 모를 500 이 나간다. 미리 확인하고 다시 뽑되 <b>시도 횟수에 상한을
     * 둔다.</b> 상한이 없으면 코드 공간이 차 있을 때 무한 반복이 된다.
     */
    private String uniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = codeGenerator.generate();
            if (reservationRepo.findByConfirmationCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException(
                "예약 확인 코드를 10회 시도 안에 만들지 못했습니다. 코드 공간을 넓혀야 합니다.");
    }
}
