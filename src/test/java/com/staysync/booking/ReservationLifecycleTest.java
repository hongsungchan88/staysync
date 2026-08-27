package com.staysync.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.staysync.booking.domain.IllegalReservationTransition;
import com.staysync.booking.domain.InsufficientInventoryException;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.ReservationStatus;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 예약 생애주기와 재고의 관계. 완료 조건 1~5, 8.
 *
 * <p>단계마다 {@code inventory_ledger} 를 직접 읽어 계획서 5.4 의 재고 영향표와 맞는지
 * 확인한다. 상태만 보면 원장이 어긋나도 통과하기 때문이다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class ReservationLifecycleTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private HoldExpiryJob holdExpiryJob;

    @Autowired
    private ReservationRepository reservationRepo;

    @Autowired
    private ReservationNightRepository nightRepo;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    // --- 완료 조건 1 ---------------------------------------------------------

    @Test
    @DisplayName("HOLD 부터 체크아웃까지 각 단계에서 재고가 5.4 표와 일치한다")
    void 전_과정에서_재고가_표와_일치한다() {
        Fixture f = given("성수동 오피스텔", (short) 1);
        StayPeriod period = period(3, 5);

        // 신규 HOLD — held +1, booked 0
        Reservation reservation = bookingService.hold(
                f.propertyId(), f.unitId(), period, BigDecimal.valueOf(300000), null);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.HOLD);
        assertLedger(f.unitId(), period, 1, 0);

        // HOLD → CONFIRMED — held −1, booked +1
        bookingService.confirm(reservation.getId());
        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.CONFIRMED);
        assertLedger(f.unitId(), period, 0, 1);

        // CONFIRMED → CHECKED_IN — 재고 변화 없음
        bookingService.checkIn(reservation.getId());
        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.CHECKED_IN);
        assertLedger(f.unitId(), period, 0, 1);

        // CHECKED_IN → CHECKED_OUT — 재고 변화 없음
        bookingService.checkOut(reservation.getId());
        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.CHECKED_OUT);
        assertLedger(f.unitId(), period, 0, 1);
    }

    @Test
    @DisplayName("수기 예약은 HOLD 를 거치지 않고 바로 booked 를 차지한다")
    void 수기_예약은_즉시_확정된다() {
        Fixture f = given("작은방", (short) 1);
        StayPeriod period = period(10, 12);

        Reservation reservation = bookingService.registerManual(
                f.propertyId(), f.unitId(), period, BigDecimal.valueOf(200000),
                (short) 2, (short) 0, null);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getChannelCode()).isEqualTo("DIRECT");
        assertThat(reservation.getConfirmationCode()).matches("SS-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}");
        assertLedger(f.unitId(), period, 0, 1);
    }

    @Test
    @DisplayName("수기 예약은 재고가 모자라면 거절된다")
    void 수기_예약은_재고가_모자라면_거절된다() {
        Fixture f = given("독채", (short) 1);
        StayPeriod period = period(20, 22);
        bookingService.registerManual(f.propertyId(), f.unitId(), period,
                BigDecimal.valueOf(100000), (short) 2, (short) 0, null);

        // 이미 성사된 OTA 예약과 달리 수기 등록은 아직 성사되지 않았으므로 막는 것이 맞다.
        assertThatThrownBy(() -> bookingService.registerManual(
                f.propertyId(), f.unitId(), period, BigDecimal.valueOf(100000),
                (short) 2, (short) 0, null))
                .isInstanceOf(InsufficientInventoryException.class);

        assertLedger(f.unitId(), period, 0, 1);
    }

    // --- 완료 조건 2 ---------------------------------------------------------

    @Test
    @DisplayName("HOLD 를 만료시키면 EXPIRED 가 되고 재고가 정확히 되돌아온다")
    void 만료된_HOLD는_재고를_되돌린다() {
        Fixture f = given("만료용", (short) 1);
        StayPeriod period = period(30, 32);

        Reservation reservation = bookingService.hold(
                f.propertyId(), f.unitId(), period, BigDecimal.valueOf(150000), null);
        assertLedger(f.unitId(), period, 1, 0);

        // 스케줄러를 기다리지 않고 메서드를 직접 부른다. 만료 시각을 지난 시점으로 준다.
        int expired = holdExpiryJob.expireDueHolds(OffsetDateTime.now().plusHours(1));

        assertThat(expired).isEqualTo(1);
        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.EXPIRED);
        assertLedger(f.unitId(), period, 0, 0);
    }

    @Test
    void 만료_시각이_지나지_않은_HOLD는_건드리지_않는다() {
        Fixture f = given("아직살아있음", (short) 1);
        StayPeriod period = period(40, 42);
        Reservation reservation = bookingService.hold(
                f.propertyId(), f.unitId(), period, BigDecimal.valueOf(100000), null);

        holdExpiryJob.expireDueHolds(OffsetDateTime.now());

        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.HOLD);
        assertLedger(f.unitId(), period, 1, 0);
    }

    // --- 완료 조건 3 ---------------------------------------------------------

    @Test
    @DisplayName("체크아웃된 예약은 취소할 수 없다")
    void 체크아웃된_예약을_취소하면_예외가_난다() {
        Fixture f = given("종착점", (short) 1);
        StayPeriod period = period(50, 52);
        Reservation reservation = bookingService.registerManual(
                f.propertyId(), f.unitId(), period, BigDecimal.valueOf(100000),
                (short) 2, (short) 0, null);
        bookingService.checkIn(reservation.getId());
        bookingService.checkOut(reservation.getId());

        assertThatThrownBy(() -> bookingService.cancel(reservation.getId()))
                .isInstanceOf(IllegalReservationTransition.class);

        // 예외가 났어도 재고는 그대로여야 한다
        assertLedger(f.unitId(), period, 0, 1);
    }

    @Test
    void 확정되지_않은_예약은_체크인할_수_없다() {
        Fixture f = given("HOLD상태", (short) 1);
        Reservation reservation = bookingService.hold(
                f.propertyId(), f.unitId(), period(60, 62), BigDecimal.valueOf(100000), null);

        assertThatThrownBy(() -> bookingService.checkIn(reservation.getId()))
                .isInstanceOf(IllegalReservationTransition.class);
    }

    @Test
    @DisplayName("NO_SHOW 는 재고를 되돌리지 않는다")
    void 노쇼는_재고를_유지한다() {
        Fixture f = given("노쇼", (short) 1);
        StayPeriod period = period(70, 72);
        Reservation reservation = bookingService.registerManual(
                f.propertyId(), f.unitId(), period, BigDecimal.valueOf(100000),
                (short) 2, (short) 0, null);

        bookingService.markNoShow(reservation.getId());

        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.NO_SHOW);
        // 방은 비었지만 요금은 받으므로 booked 를 유지한다
        assertLedger(f.unitId(), period, 0, 1);
    }

    // --- 완료 조건 4 ---------------------------------------------------------

    @Test
    @DisplayName("같은 예약을 두 번 취소해도 재고는 한 번만 반납된다")
    void 두_번_취소해도_재고는_한_번만_반납된다() {
        Fixture f = given("멱등취소", (short) 2);
        StayPeriod period = period(80, 82);
        Reservation reservation = bookingService.registerManual(
                f.propertyId(), f.unitId(), period, BigDecimal.valueOf(100000),
                (short) 2, (short) 0, null);
        assertLedger(f.unitId(), period, 0, 1);

        bookingService.cancel(reservation.getId());
        assertLedger(f.unitId(), period, 0, 0);

        // 두 번째 취소는 아무 일도 하지 않아야 한다. 재고를 두 번 반납하면 booked 가
        // 음수가 되거나 CHECK 제약에 걸린다.
        bookingService.cancel(reservation.getId());

        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.CANCELLED);
        assertLedger(f.unitId(), period, 0, 0);
    }

    // --- 완료 조건 5 ---------------------------------------------------------

    @Test
    @DisplayName("날짜 변경에서 새 기간이 모자라면 옛 기간도 그대로 남는다")
    void 날짜_변경이_실패하면_옛_기간이_그대로_남는다() {
        Fixture f = given("부분실패", (short) 1);
        StayPeriod original = period(100, 102);
        StayPeriod blocked = period(110, 112);

        Reservation moving = bookingService.registerManual(
                f.propertyId(), f.unitId(), original, BigDecimal.valueOf(100000),
                (short) 2, (short) 0, null);
        // 옮겨 가려는 기간을 다른 예약이 미리 채운다
        bookingService.registerManual(f.propertyId(), f.unitId(), blocked,
                BigDecimal.valueOf(100000), (short) 2, (short) 0, null);

        assertThatThrownBy(() -> bookingService.changeStay(
                moving.getId(), blocked, (short) 2, (short) 0))
                .isInstanceOf(InsufficientInventoryException.class);

        // 옛 기간의 재고가 반납된 채로 남으면 안 된다. 이게 유령 재고다.
        assertLedger(f.unitId(), original, 0, 1);
        // 새 기간은 먼저 있던 예약 한 건만 차지하고 있어야 한다
        assertLedger(f.unitId(), blocked, 0, 1);
        // 예약의 날짜도 바뀌지 않아야 한다
        Reservation reloaded = reservationRepo.findById(moving.getId()).orElseThrow();
        assertThat(reloaded.getPeriod().checkIn()).isEqualTo(original.checkIn());
    }

    @Test
    void 날짜를_옮기면_옛_기간이_비고_새_기간이_찬다() {
        Fixture f = given("정상이동", (short) 1);
        StayPeriod original = period(120, 122);
        StayPeriod moved = period(130, 132);

        Reservation reservation = bookingService.registerManual(
                f.propertyId(), f.unitId(), original, BigDecimal.valueOf(100000),
                (short) 2, (short) 0, null);

        bookingService.changeStay(reservation.getId(), moved, (short) 3, (short) 1);

        assertLedger(f.unitId(), original, 0, 0);
        assertLedger(f.unitId(), moved, 0, 1);
    }

    @Test
    @DisplayName("겹치는 기간으로 하루 연장할 수 있다")
    void 하루_연장은_재고가_하나여도_된다() {
        Fixture f = given("연장", (short) 1);
        StayPeriod original = period(140, 142);
        StayPeriod extended = period(140, 143);

        Reservation reservation = bookingService.registerManual(
                f.propertyId(), f.unitId(), original, BigDecimal.valueOf(100000),
                (short) 2, (short) 0, null);

        // 새 기간을 먼저 잡으려 하면 겹치는 날짜에 같은 예약이 두 자리를 차지해 실패한다.
        // 반납이 먼저여야 이 흔한 변경이 통과한다.
        bookingService.changeStay(reservation.getId(), extended, (short) 2, (short) 0);

        assertLedger(f.unitId(), extended, 0, 1);
    }

    // --- 완료 조건 8 ---------------------------------------------------------

    @Test
    @DisplayName("박별 요금의 합이 총액과 정확히 같다")
    void 박별_요금의_합이_총액과_같다() {
        Fixture f = given("금액분배", (short) 1);

        // 나누어떨어지는 금액
        assertNightsSumTo(f, period(150, 152), new BigDecimal("200000.00"));
        // 나누어떨어지지 않는 금액. 100000 / 3 = 33333.33... 이라 그냥 더하면 어긋난다.
        assertNightsSumTo(f, period(160, 163), new BigDecimal("100000.00"));
        // 1원 단위까지 어긋나는 금액
        assertNightsSumTo(f, period(170, 177), new BigDecimal("100000.01"));
    }

    private void assertNightsSumTo(Fixture f, StayPeriod period, BigDecimal total) {
        Reservation reservation = bookingService.registerManual(
                f.propertyId(), f.unitId(), period, total, (short) 2, (short) 0, null);

        BigDecimal sum = nightRepo.findByReservationIdOrderByStayDateAsc(reservation.getId())
                .stream()
                .map(com.staysync.booking.domain.ReservationNight::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sum)
                .as("%d박 %s 원의 박별 합계", period.nights(), total)
                .isEqualByComparingTo(total);
    }

    // --- 헬퍼 ---------------------------------------------------------------

    private record Fixture(Long propertyId, Long unitId) {
    }

    private Fixture given(String name, short totalUnits) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('예약테스트') RETURNING id", Long.class);
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        Long unitId = unitRegistration.register(
                propertyId, name, UnitKind.ENTIRE_PLACE, totalUnits, BigDecimal.valueOf(100000));
        return new Fixture(propertyId, unitId);
    }

    /** 테스트끼리 날짜가 겹치지 않게 기준일에서 밀어 쓴다. */
    private static StayPeriod period(int fromOffset, int toOffset) {
        LocalDate base = LocalDate.of(2027, 1, 1);
        return new StayPeriod(base.plusDays(fromOffset), base.plusDays(toOffset));
    }

    private ReservationStatus statusOf(Reservation reservation) {
        return reservationRepo.findById(reservation.getId()).orElseThrow().getStatus();
    }

    /** 기간의 모든 날짜에서 held 와 booked 가 기대값과 같은지 확인한다. */
    private void assertLedger(Long unitId, StayPeriod period, int expectedHeld, int expectedBooked) {
        period.nightDates().forEach(date -> {
            Integer held = jdbc.queryForObject("""
                    SELECT COALESCE((SELECT held_units FROM inventory_ledger
                                      WHERE unit_id = ? AND stay_date = ?), 0)
                    """, Integer.class, unitId, date);
            Integer booked = jdbc.queryForObject("""
                    SELECT COALESCE((SELECT booked_units FROM inventory_ledger
                                      WHERE unit_id = ? AND stay_date = ?), 0)
                    """, Integer.class, unitId, date);
            assertThat(held).as("%s 의 held", date).isEqualTo(expectedHeld);
            assertThat(booked).as("%s 의 booked", date).isEqualTo(expectedBooked);
        });
    }
}
