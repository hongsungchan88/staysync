package com.staysync.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.staysync.booking.BookingService;
import com.staysync.booking.domain.CheckOutUndoExpiredException;
import com.staysync.booking.domain.IllegalReservationTransition;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.ops.domain.OpsTask;
import com.staysync.ops.domain.TaskStatus;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import com.staysync.shared.outbox.OutboxRelay;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 체크인 취소·체크아웃 되돌리기(작업지시-20 9절).
 *
 * <p>{@code CleaningTaskTest} 와 같은 컨텍스트다(속성이 같아야 캐시를 나눠 쓴다). 청소 태스크는
 * 실제 경로 — 체크아웃 → Outbox → 릴레이 → 소비자 — 로 만든다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class CheckInOutUndoTest {

    private static final LocalDate 체크인 = LocalDate.of(2027, 5, 1);
    private static final LocalDate 체크아웃 = 체크인.plusDays(2);

    @Autowired
    private BookingService booking;

    @Autowired
    private OpsTaskService tasks;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("체크인 취소 — 확정으로 돌아가고, 재고는 그대로고, 한 일과 되돌린 일이 둘 다 감사에 남는다")
    void 체크인을_취소하면_확정으로_돌아간다() {
        Fixture f = given("체크인취소");
        Long id = 예약(f);
        booking.checkIn(id);
        int 전 = booked(f);

        Reservation undone = booking.undoCheckIn(id, "다른 객실 손님을 잘못 눌렀다");

        assertThat(undone.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(booked(f)).isEqualTo(전);
        assertThat(감사(id)).containsExactly("MANUAL_CREATE", "CHECK_IN", "CHECK_IN_UNDONE");
        assertThat(사유(id, "CHECK_IN_UNDONE")).isEqualTo("다른 객실 손님을 잘못 눌렀다");
        // 다시 체크인할 수 있다.
        assertThat(booking.checkIn(id).getStatus().name()).isEqualTo("CHECKED_IN");
    }

    @Test
    @DisplayName("체크인하지 않은 예약은 체크인을 취소할 수 없다")
    void 확정_예약은_체크인_취소가_안_된다() {
        Fixture f = given("체크인취소불가");
        Long id = 예약(f);

        assertThatThrownBy(() -> booking.undoCheckIn(id, "사유"))
                .isInstanceOf(IllegalReservationTransition.class);
    }

    @Test
    @DisplayName("체크아웃 되돌리기 — 할 일 상태의 청소 태스크를 거두고 재고는 그대로다")
    void 체크아웃을_되돌리면_청소_태스크를_거둔다() {
        Fixture f = given("체크아웃되돌리기");
        Long id = 예약(f);
        int 전 = booked(f);
        booking.checkIn(id);
        booking.checkOut(id);
        relay.relayPending();
        assertThat(tasks.board(f.orgId(), null, null, null)).hasSize(1);
        assertThat(booked(f)).as("체크아웃은 재고를 반납하지 않는다 — 되돌려도 초과 판매가 없는 근거").isEqualTo(전);

        Reservation undone = booking.undoCheckOut(id, "손님이 아직 방에 있다");

        assertThat(undone.getStatus().name()).isEqualTo("CHECKED_IN");
        assertThat(tasks.board(f.orgId(), null, null, null)).isEmpty();
        assertThat(booked(f)).isEqualTo(전);
        assertThat(감사(id)).containsExactly("MANUAL_CREATE", "CHECK_IN", "CHECK_OUT", "CHECK_OUT_UNDONE");
        assertThat(사유(id, "CHECK_OUT_UNDONE")).isEqualTo("손님이 아직 방에 있다");

        // 다시 체크아웃하면 태스크가 다시 생긴다.
        booking.checkOut(id);
        relay.relayPending();
        assertThat(tasks.board(f.orgId(), null, null, null)).hasSize(1);
    }

    @Test
    @DisplayName("청소를 이미 시작했으면 체크아웃을 되돌리지 않는다 — 아무것도 바뀌지 않는다")
    void 청소가_진행중이면_되돌리기를_막는다() {
        Fixture f = given("청소진행중");
        Long id = 예약(f);
        booking.checkIn(id);
        booking.checkOut(id);
        relay.relayPending();
        OpsTask task = tasks.board(f.orgId(), null, null, null).get(0);
        tasks.move(task.getId(), f.orgId(), TaskStatus.IN_PROGRESS);

        assertThatThrownBy(() -> booking.undoCheckOut(id, "사유"))
                .isInstanceOf(CleaningAlreadyStartedException.class);

        assertThat(상태(id)).isEqualTo("CHECKED_OUT");
        assertThat(tasks.board(f.orgId(), null, null, null)).hasSize(1);
        assertThat(감사(id)).doesNotContain("CHECK_OUT_UNDONE");
    }

    @Test
    @DisplayName("릴레이가 태스크를 만들기 전에 되돌리면 늦게 온 이벤트가 태스크를 만들지 않는다")
    void 이벤트보다_먼저_되돌려도_태스크가_남지_않는다() {
        Fixture f = given("늦은이벤트");
        Long id = 예약(f);
        booking.checkIn(id);
        booking.checkOut(id);

        booking.undoCheckOut(id, "바로 되돌림");
        relay.relayPending();

        assertThat(tasks.board(f.orgId(), null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("퇴실일이 지나면 체크아웃을 되돌릴 수 없다")
    void 퇴실일이_지나면_되돌릴_수_없다() {
        Fixture f = given("퇴실일지남");
        Long id = 예약(f);
        booking.checkIn(id);
        booking.checkOut(id);
        // 판정은 예약의 퇴실일 하나만 본다. 지난 예약을 만드는 대신 그 날짜만 옮긴다.
        jdbc.update("UPDATE reservation SET check_in = ?, check_out = ? WHERE id = ?",
                LocalDate.now().minusDays(4), LocalDate.now().minusDays(2), id);

        assertThatThrownBy(() -> booking.undoCheckOut(id, "사유"))
                .isInstanceOf(CheckOutUndoExpiredException.class);
        assertThat(상태(id)).isEqualTo("CHECKED_OUT");
    }

    // --- 도우미 --------------------------------------------------------------

    private Long 예약(Fixture f) {
        return booking.registerManual(f.propertyId(), f.unitId(), new StayPeriod(체크인, 체크아웃),
                BigDecimal.valueOf(200_000), (short) 2, (short) 0, null).getId();
    }

    private int booked(Fixture f) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(booked_units), 0) FROM inventory_ledger WHERE unit_id = ?",
                Integer.class, f.unitId());
    }

    private String 상태(Long id) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id);
    }

    private List<String> 감사(Long id) {
        return jdbc.queryForList("""
                SELECT action FROM audit_log WHERE entity_type = 'RESERVATION' AND entity_id = ?
                ORDER BY id""", String.class, id);
    }

    private String 사유(Long id, String action) {
        return jdbc.queryForObject("""
                SELECT after_value ->> 'reason' FROM audit_log
                WHERE entity_type = 'RESERVATION' AND entity_id = ? AND action = ?""",
                String.class, id, action);
    }

    private Fixture given(String name) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES (?) RETURNING id", Long.class, name + " 조직");
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        Long unitId = unitRegistration.register(propertyId, name + " 객실", UnitKind.ENTIRE_PLACE,
                (short) 1, BigDecimal.valueOf(100_000));
        return new Fixture(orgId, propertyId, unitId);
    }

    private record Fixture(Long orgId, Long propertyId, Long unitId) {
    }
}
