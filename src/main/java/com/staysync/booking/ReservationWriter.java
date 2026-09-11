package com.staysync.booking;

import com.staysync.booking.domain.*;
import com.staysync.shared.audit.AuditRecorder;
import com.staysync.shared.outbox.OutboxRecorder;
import java.math.BigDecimal;
import java.util.Map;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예약 상태 전이와 재고 변경을 <b>한 트랜잭션에</b> 담는 경계.
 *
 * <p>이 클래스의 존재 이유가 그 한 문장이다. 예약 상태가 바뀌었는데 원장이 안 바뀌면,
 * 또는 그 반대면, 캘린더가 거짓말을 한다. 초과 판매나 팔 수 있는 방을 못 파는 상태가
 * 되고 둘 다 조용히 일어난다. 두 쓰기는 함께 성공하거나 함께 실패해야 한다.
 *
 * <p><b>ADR 0005 의 {@code REQUIRES_NEW} 를 여기에 가져오면 안 된다.</b> 저기는 성격이
 * 반대다. 재사용 탐지는 "실패로 끝나면서 기록은 남겨야" 하므로 기록을 별도 트랜잭션에
 * 담았다. 여기는 "함께 성공하거나 함께 실패해야" 하므로 같은 트랜잭션이어야 한다.
 * 판별 기준은 하나다 — <b>바깥이 롤백될 때 이 쓰기가 남아야 하는가, 사라져야 하는가.</b>
 *
 * <p>{@link InventoryService} 를 그대로 부른다. 그쪽이 다시 락을 잡지만
 * {@code ReentrantLock} 이라 같은 스레드에서는 재진입이 되고, 원장을 쓰는
 * {@code InventoryLedgerWriter.apply()} 는 전파가 {@code REQUIRED} 라 여기서 연
 * 트랜잭션에 합류한다. 원장을 직접 건드리지 않으므로 방어 계층도 그대로 지나간다.
 *
 * <p>락은 {@link BookingService} 가 이 빈을 부르기 <i>전에</i> 잡는다. 순서가 반대면
 * 트랜잭션이 열린 채 락을 기다려 커넥션 풀이 마른다.
 */
@Component
class ReservationWriter {

    private static final Logger log = LoggerFactory.getLogger(ReservationWriter.class);

    /**
     * 예약 한 건이 차지하는 재고 수량.
     *
     * <p>이 프로젝트에서 예약은 항상 판매 단위 하나를 차지한다. 도미토리에서 침대 두
     * 자리를 한 번에 예약하는 경우는 예약 두 건으로 표현한다.
     */
    private static final int UNITS = 1;

    private final ReservationRepository reservationRepo;
    private final ReservationNightRepository nightRepo;
    private final InventoryService inventoryService;
    private final OutboxRecorder outbox;
    private final AuditRecorder audit;

    ReservationWriter(ReservationRepository reservationRepo,
                      ReservationNightRepository nightRepo,
                      InventoryService inventoryService,
                      OutboxRecorder outbox,
                      AuditRecorder audit) {
        this.reservationRepo = reservationRepo;
        this.nightRepo = nightRepo;
        this.inventoryService = inventoryService;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** 수기 예약. 재고를 먼저 확보하고 예약을 만든다. 모자라면 전체가 실패한다. */
    @Transactional
    Reservation createConfirmed(Long propertyId, Long unitId, StayPeriod period,
                               String confirmationCode, BigDecimal totalAmount,
                               short adults, short children, Long guestId) {
        inventoryService.reserve(unitId, period, UNITS);

        Reservation reservation = Reservation.manual(
                propertyId, unitId, period, confirmationCode, totalAmount, adults, children);
        if (guestId != null) {
            reservation.assignGuest(guestId);
        }
        Reservation saved = reservationRepo.saveAndFlush(reservation);
        writeNights(saved);
        recordConfirmed(saved, "MANUAL_CREATE", null);
        return saved;
    }

    /** 임시 점유. 직접예약이 결제를 기다리는 상태다. */
    @Transactional
    Reservation createHold(Long propertyId, Long unitId, StayPeriod period,
                          String confirmationCode, BigDecimal totalAmount,
                          OffsetDateTime expiresAt, Long guestId,
                          short adults, short children) {
        inventoryService.hold(unitId, period, UNITS);

        Reservation reservation = Reservation.directHold(
                propertyId, unitId, period, confirmationCode, totalAmount, expiresAt,
                adults, children);
        if (guestId != null) {
            reservation.assignGuest(guestId);
        }
        Reservation saved = reservationRepo.saveAndFlush(reservation);
        writeNights(saved);

        // 재고가 줄었다는 사실을 알린다. P4 16주차에 더했다.
        //
        // 예전 주석은 "확정되지 않은 점유를 바깥이 알 이유가 없다"였다. 그러나 채널이
        // 알아야 하는 것은 홀드가 아니라 **재고가 바뀌었다는 사실**이고,
        // available() = total - booked - held 이므로 HOLD 는 채널에 나갈 재고를 곧바로
        // 줄인다. 알리지 않으면 홀드 창 동안 채널이 옛 재고를 들고 있고, 그 사이 채널
        // 예약이 들어오면 초과 판매 충돌이 된다.
        //
        // 원칙을 뒤집은 것이 아니라 **비대칭을 맞춘 것이다.** 만료(EXPIRED)는 이미
        // 채널로 나가고 있었다 — 재고가 느는 쪽만 알리고 줄어드는 쪽은 알리지 않았다.
        outbox.record(ReservationEvents.AGGREGATE_TYPE, saved.getId(),
                ReservationEvents.HELD, ReservationEvents.payloadOf(saved));
        audit.record(ReservationEvents.AGGREGATE_TYPE, saved.getId(), "HOLD_CREATE",
                null, ReservationEvents.auditSnapshot(saved));
        return saved;
    }

    /** HOLD → CONFIRMED. 점유를 확정으로 승격한다. */
    @Transactional
    Reservation confirm(Long reservationId) {
        Reservation reservation = load(reservationId);
        Map<String, Object> before = ReservationEvents.auditSnapshot(reservation);

        reservation.confirm();
        inventoryService.promoteHold(reservation.getUnitId(), reservation.getPeriod(), UNITS);

        recordConfirmed(reservation, "CONFIRM", before);
        return reservation;
    }

    /**
     * 취소. 멱등하다.
     *
     * <p>이미 취소된 예약이면 재고를 <b>다시 반납하지 않는다.</b> 두 번 반납하면 원장이
     * 틀어져 있지도 않은 재고를 팔게 된다. 그래서 상태를 먼저 보고 갈린다.
     */
    @Transactional
    Reservation cancel(Long reservationId) {
        Reservation reservation = load(reservationId);
        ReservationStatus before = reservation.getStatus();

        reservation.cancel();   // CHECKED_OUT 이면 여기서 예외가 난다

        if (before == ReservationStatus.CANCELLED) {
            // 멱등하다. 상태도 재고도 바뀌지 않았으므로 이벤트와 감사도 남기지 않는다.
            // 아무 일도 없었는데 "취소했다"는 이벤트가 나가면 소비자가 두 번 처리한다.
            log.debug("이미 취소된 예약이라 재고를 되돌리지 않는다. id={}", reservationId);
            return reservation;
        }
        switch (before) {
            case HOLD -> inventoryService.releaseHold(
                    reservation.getUnitId(), reservation.getPeriod(), UNITS);
            case CONFIRMED, CHECKED_IN -> inventoryService.release(
                    reservation.getUnitId(), reservation.getPeriod(), UNITS);
            default -> log.debug("재고를 점유하지 않은 상태라 되돌릴 것이 없다. status={}", before);
        }

        outbox.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(),
                ReservationEvents.CANCELLED, ReservationEvents.payloadOf(reservation));
        audit.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(), "CANCEL",
                ReservationEvents.auditSnapshot(before.name(),
                        reservation.getPeriod().checkIn().toString(),
                        reservation.getPeriod().checkOut().toString()),
                ReservationEvents.auditSnapshot(reservation));
        return reservation;
    }

    /** CONFIRMED → CHECKED_IN. 재고는 그대로다. 이미 팔린 방이다. */
    @Transactional
    Reservation checkIn(Long reservationId) {
        Reservation reservation = load(reservationId);
        Map<String, Object> before = ReservationEvents.auditSnapshot(reservation);

        reservation.checkIn();

        // 이벤트를 만들지 않는다. 체크인은 아직 소비자가 없고, 없는 소비자를 위해
        // 이벤트를 만들면 발행 비용만 든다. 감사는 남긴다.
        audit.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(), "CHECK_IN",
                before, ReservationEvents.auditSnapshot(reservation));
        return reservation;
    }

    /** CHECKED_IN → CHECKED_OUT. 종착점이다. 재고는 그대로다. */
    @Transactional
    Reservation checkOut(Long reservationId) {
        Reservation reservation = load(reservationId);
        Map<String, Object> before = ReservationEvents.auditSnapshot(reservation);

        reservation.checkOut();

        outbox.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(),
                ReservationEvents.CHECKED_OUT, ReservationEvents.payloadOf(reservation));
        audit.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(), "CHECK_OUT",
                before, ReservationEvents.auditSnapshot(reservation));
        return reservation;
    }

    /** CONFIRMED → NO_SHOW. 방은 비었지만 요금은 받으므로 재고를 되돌리지 않는다. */
    @Transactional
    Reservation markNoShow(Long reservationId) {
        Reservation reservation = load(reservationId);
        Map<String, Object> before = ReservationEvents.auditSnapshot(reservation);

        reservation.markNoShow();

        // 재고가 그대로라 채널에 알릴 것이 없다. 감사는 남긴다.
        audit.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(), "NO_SHOW",
                before, ReservationEvents.auditSnapshot(reservation));
        return reservation;
    }

    /** HOLD → EXPIRED. 만료 배치가 부른다. */
    @Transactional
    void expire(Long reservationId) {
        Reservation reservation = load(reservationId);
        if (reservation.getStatus() != ReservationStatus.HOLD) {
            // 배치가 목록을 읽은 뒤 사용자가 결제를 마쳤을 수 있다. 그냥 넘어간다.
            return;
        }
        Map<String, Object> before = ReservationEvents.auditSnapshot(reservation);

        reservation.expire();
        inventoryService.releaseHold(reservation.getUnitId(), reservation.getPeriod(), UNITS);

        // 취소와 마찬가지로 재고가 풀린다. 채널이 다시 팔 수 있게 되었다는 사실을
        // 알아야 하므로 이벤트를 만든다.
        outbox.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(),
                ReservationEvents.EXPIRED, ReservationEvents.payloadOf(reservation));
        // 배치가 부르는 경로라 SecurityContext 가 없다. AuditRecorder 가 알아서
        // actor_kind 를 SYSTEM 으로 남기고 actor_id 는 비운다.
        audit.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(), "HOLD_EXPIRE",
                before, ReservationEvents.auditSnapshot(reservation));
    }

    /**
     * 날짜와 인원 변경.
     *
     * <p>이 API 에서 가장 어려운 연산이다. 옛 기간의 재고를 되돌리고 새 기간을 잡아야
     * 하는데, <b>새 기간이 모자라면 전부 없던 일이 되어야 한다.</b> 옛 기간만 반납된 채
     * 끝나면 팔리지 않은 방이 팔린 것처럼, 또는 그 반대로 남는다.
     *
     * <p>한 트랜잭션이라 새 기간 확보가 실패하면 옛 기간 반납까지 함께 롤백된다.
     * 중간 상태가 남지 않는다.
     *
     * <p>반드시 <b>반납이 먼저</b>다. 두 기간이 겹칠 때 새 기간을 먼저 잡으려 하면 겹치는
     * 날짜에 같은 예약이 두 자리를 차지해 재고가 하나뿐인 숙소에서는 늘 실패한다.
     * 하루 연장 같은 흔한 변경이 막힌다.
     *
     * <p>두 기간의 날짜를 오름차순 합집합으로 잠그는 것과 같은 효과는 판매 단위 락이
     * 만든다. 같은 판매 단위에 대해서는 이 블록에 한 스레드만 들어오고, 다른 판매 단위는
     * 원장 행이 겹치지 않아 교착 상태가 생길 수 없다.
     */
    @Transactional
    Reservation changeStay(Long reservationId, StayPeriod newPeriod,
                          short adults, short children) {
        Reservation reservation = load(reservationId);
        StayPeriod oldPeriod = reservation.getPeriod();
        Long unitId = reservation.getUnitId();

        boolean sameDates = oldPeriod.checkIn().equals(newPeriod.checkIn())
                && oldPeriod.checkOut().equals(newPeriod.checkOut());

        if (!sameDates) {
            switch (reservation.getStatus()) {
                case HOLD -> {
                    inventoryService.releaseHold(unitId, oldPeriod, UNITS);
                    inventoryService.hold(unitId, newPeriod, UNITS);
                }
                case CONFIRMED, CHECKED_IN -> {
                    inventoryService.release(unitId, oldPeriod, UNITS);
                    inventoryService.reserve(unitId, newPeriod, UNITS);
                }
                default -> throw new IllegalReservationTransition(
                        reservation.getStatus(), reservation.getStatus());
            }
        }

        Map<String, Object> before = ReservationEvents.auditSnapshot(reservation);
        reservation.changeStay(newPeriod, adults, children);
        if (!sameDates) {
            rewriteNights(reservation);
            outbox.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(),
                    ReservationEvents.DATES_CHANGED, ReservationEvents.payloadOf(reservation));
        }
        audit.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(), "CHANGE_STAY",
                before, ReservationEvents.auditSnapshot(reservation));
        return reservation;
    }

    /**
     * 판매 단위를 옮긴다. 충돌 해소의 <b>업그레이드 배정</b>이 부른다(계획서 7.4).
     *
     * <p>날짜는 그대로이고 방만 바뀐다. 옛 단위의 재고를 반납하고 새 단위에서 잡는데,
     * <b>새 단위가 모자라면 전부 없던 일이 되어야 한다.</b> 한 트랜잭션이라 새 단위
     * 확보가 실패하면 반납까지 함께 롤백된다.
     *
     * <p><b>락 둘을 여기서 잡지 않는다.</b> {@code ConflictResolutionService} 가 이
     * 빈을 부르기 <i>전에</i> 판매 단위 식별자 오름차순으로 잡는다. 여기서 잡으면
     * 트랜잭션이 열린 채 락을 기다려 커넥션 풀이 마르고, 무엇보다 순서를 정할 자리가
     * 트랜잭션 안이 되어 ADR 0002 가 예고한 교착이 그대로 난다.
     *
     * <p>{@code InventoryService} 가 안에서 다시 락을 잡지만 {@code ReentrantLock}
     * 이라 같은 스레드에서는 재진입이 된다. 일괄 편집이 이미 그렇게 부른다.
     */
    @Transactional
    Reservation moveToUnit(Long reservationId, Long targetUnitId) {
        Reservation reservation = load(reservationId);
        Long sourceUnitId = reservation.getUnitId();
        if (sourceUnitId.equals(targetUnitId)) {
            return reservation;
        }
        Map<String, Object> before = ReservationEvents.auditSnapshot(reservation);
        StayPeriod period = reservation.getPeriod();

        // 반납이 먼저다. 순서가 반대면 두 단위가 같은 기간을 동시에 잡는 구간이
        // 생기고, 재고가 하나뿐인 단위에서는 늘 실패한다.
        switch (reservation.getStatus()) {
            case HOLD -> {
                inventoryService.releaseHold(sourceUnitId, period, UNITS);
                inventoryService.hold(targetUnitId, period, UNITS);
            }
            case CONFIRMED, CHECKED_IN -> {
                inventoryService.release(sourceUnitId, period, UNITS);
                inventoryService.reserve(targetUnitId, period, UNITS);
            }
            default -> throw new IllegalReservationTransition(
                    reservation.getStatus(), reservation.getStatus());
        }

        reservation.moveToUnit(targetUnitId);
        rewriteNights(reservation);

        // 두 단위의 재고가 함께 바뀌었으므로 이벤트도 둘이다. 하나만 남기면
        // 옛 단위의 늘어난 재고가 어느 채널에도 나가지 않고, 팔 수 있는 방을
        // 못 파는 상태가 된다. 그때 우리 쪽 로그는 전부 정상이다.
        outbox.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(),
                ReservationEvents.CANCELLED,
                ReservationEvents.releasedUnitPayload(reservation, sourceUnitId));
        outbox.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(),
                ReservationEvents.CONFIRMED, ReservationEvents.payloadOf(reservation));
        audit.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(), "MOVE_UNIT",
                before, ReservationEvents.auditSnapshot(reservation));
        return reservation;
    }

    /** 확정 이벤트와 감사를 함께 남긴다. 수기 등록과 HOLD 승격이 같은 사건을 만든다. */
    private void recordConfirmed(Reservation reservation, String action,
                                 Map<String, Object> before) {
        outbox.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(),
                ReservationEvents.CONFIRMED, ReservationEvents.payloadOf(reservation));
        audit.record(ReservationEvents.AGGREGATE_TYPE, reservation.getId(), action,
                before, ReservationEvents.auditSnapshot(reservation));
    }

    private Reservation load(Long reservationId) {
        return reservationRepo.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    /**
     * 박 행을 쓴다. 리포트의 판매 객실박·매출·채널 믹스가 이 테이블을 읽는다.
     *
     * <p>패키지에 열어 둔 이유는 {@code ChannelBookingWriter} 다. 채널 수신이 이걸
     * 부르지 않아 채널 예약이 리포트에서 통째로 빠졌다(확인-08 3절 1번). 재고 원장은
     * 정상이라 캘린더는 맞게 보이고 리포트만 조용히 적게 셌다.
     */
    void writeNights(Reservation reservation) {
        List<ReservationNight> nights = ReservationNight.split(
                reservation.getId(), reservation.getUnitId(),
                reservation.getPeriod(), reservation.getTotalAmount());
        nightRepo.saveAll(nights);
    }

    /** 날짜가 바뀌면 옛 박 행은 의미가 없다. 지우고 다시 쓴다. */
    void rewriteNights(Reservation reservation) {
        nightRepo.deleteByReservationId(reservation.getId());
        nightRepo.flush();
        writeNights(reservation);
    }
}
