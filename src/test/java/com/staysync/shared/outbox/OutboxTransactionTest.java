package com.staysync.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.staysync.booking.BookingService;
import com.staysync.booking.GuestRegistrar;
import com.staysync.booking.domain.InsufficientInventoryException;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
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
 * 완료 조건 1, 2, 9. Outbox 와 감사 기록의 트랜잭션 성질, 그리고 개인정보.
 *
 * <p>이 패턴 전체가 "도메인 변경과 같은 트랜잭션에서 커밋된다"는 전제 위에 서 있다.
 * 그 전제가 깨져도 정상 경로에서는 아무 증상이 없다. 롤백이 일어나는 경로를 일부러
 * 만들어야만 드러난다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class OutboxTransactionTest {

    private static final String 전화번호 = "010-7777-8888";
    private static final String 이메일 = "outbox-guest@example.com";

    @Autowired
    private BookingService bookingService;

    @Autowired
    private GuestRegistrar guestRegistrar;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    // --- 완료 조건 1 ---------------------------------------------------------

    @Test
    @DisplayName("예약 트랜잭션이 롤백되면 outbox_event 행도 남지 않는다")
    void 예약이_롤백되면_이벤트도_사라진다() {
        Fixture f = given("롤백확인", (short) 1);
        StayPeriod period = period(0, 2);

        // 먼저 재고를 채워 다음 등록이 반드시 실패하게 만든다
        bookingService.registerManual(f.propertyId(), f.unitId(), period,
                BigDecimal.valueOf(100000), (short) 2, (short) 0, null);
        int 이벤트수_실패전 = outboxCount();

        assertThatThrownBy(() -> bookingService.registerManual(
                f.propertyId(), f.unitId(), period, BigDecimal.valueOf(100000),
                (short) 2, (short) 0, null))
                .isInstanceOf(InsufficientInventoryException.class);

        // 예약이 만들어지지 않았으므로 "예약이 확정됐다"는 이벤트도 없어야 한다.
        // 일어나지 않은 일을 바깥에 알릴 수는 없다.
        assertThat(outboxCount())
                .as("롤백된 트랜잭션이 이벤트를 남기면 유령 이벤트가 된다")
                .isEqualTo(이벤트수_실패전);
    }

    @Test
    @DisplayName("날짜 변경이 실패하면 이벤트도 감사도 남지 않는다")
    void 날짜_변경이_실패하면_기록도_사라진다() {
        Fixture f = given("변경롤백", (short) 1);
        StayPeriod original = period(10, 12);
        StayPeriod blocked = period(20, 22);

        Reservation moving = bookingService.registerManual(f.propertyId(), f.unitId(), original,
                BigDecimal.valueOf(100000), (short) 2, (short) 0, null);
        bookingService.registerManual(f.propertyId(), f.unitId(), blocked,
                BigDecimal.valueOf(100000), (short) 2, (short) 0, null);

        int 이벤트수 = outboxCountOf(moving.getId());
        int 감사수 = auditCountOf(moving.getId());

        assertThatThrownBy(() -> bookingService.changeStay(
                moving.getId(), blocked, (short) 2, (short) 0))
                .isInstanceOf(InsufficientInventoryException.class);

        assertThat(outboxCountOf(moving.getId())).isEqualTo(이벤트수);
        assertThat(auditCountOf(moving.getId())).isEqualTo(감사수);
    }

    // --- 완료 조건 2 ---------------------------------------------------------

    @Test
    @DisplayName("변경이 롤백되면 감사 기록도 사라진다")
    void 변경이_롤백되면_감사도_사라진다() {
        Fixture f = given("감사롤백", (short) 1);
        StayPeriod period = period(30, 32);

        bookingService.registerManual(f.propertyId(), f.unitId(), period,
                BigDecimal.valueOf(100000), (short) 2, (short) 0, null);
        int 감사수_실패전 = auditCount();

        assertThatThrownBy(() -> bookingService.registerManual(
                f.propertyId(), f.unitId(), period, BigDecimal.valueOf(100000),
                (short) 2, (short) 0, null))
                .isInstanceOf(InsufficientInventoryException.class);

        // 변경이 롤백됐는데 "변경했다"는 기록이 남으면 그 감사 로그는 거짓말이 된다.
        assertThat(auditCount())
                .as("감사 기록이 거짓말을 하면 없느니만 못하다")
                .isEqualTo(감사수_실패전);
    }

    @Test
    void 성공한_예약은_이벤트와_감사를_남긴다() {
        Fixture f = given("정상경로", (short) 1);

        Reservation reservation = bookingService.registerManual(
                f.propertyId(), f.unitId(), period(40, 42),
                BigDecimal.valueOf(100000), (short) 2, (short) 0, null);

        assertThat(eventTypesOf(reservation.getId())).containsExactly("RESERVATION_CONFIRMED");
        assertThat(auditActionsOf(reservation.getId())).contains("MANUAL_CREATE");
    }

    // --- 완료 조건 9 ---------------------------------------------------------

    @Test
    @DisplayName("이벤트 페이로드와 감사 기록에 연락처가 들어가지 않는다")
    void 페이로드와_감사에_연락처가_없다() {
        Fixture f = given("개인정보", (short) 1);
        Long guestId = guestRegistrar.register(
                f.orgId(), "홍길동", 전화번호, 이메일).getId();

        Reservation reservation = bookingService.registerManual(
                f.propertyId(), f.unitId(), period(50, 52),
                BigDecimal.valueOf(100000), (short) 2, (short) 0, guestId);
        // 전이를 여러 번 일으켜 모든 기록 경로를 훑는다
        bookingService.checkIn(reservation.getId());
        bookingService.checkOut(reservation.getId());

        String 이벤트전체 = String.join("\n", payloadsOf(reservation.getId()));
        String 감사전체 = String.join("\n", auditValuesOf(reservation.getId()));

        // 정규화된 형태와 원본 표기를 모두 확인한다
        assertThat(이벤트전체)
                .as("페이로드에 연락처가 실리면 암호화를 우회하는 두 번째 경로가 된다")
                .doesNotContain("01077778888")
                .doesNotContain(전화번호)
                .doesNotContain(이메일)
                .doesNotContain("홍길동");
        assertThat(감사전체)
                .doesNotContain("01077778888")
                .doesNotContain(전화번호)
                .doesNotContain(이메일)
                .doesNotContain("홍길동");

        // 식별자는 들어 있어야 한다. 소비자가 필요하면 이걸로 조회해 복호화한다.
        // JSONB 는 저장하면서 공백을 넣어 정규화하므로 문자열을 그대로 비교하지 않는다.
        assertThat(이벤트전체).containsPattern("\"guestId\"\\s*:\\s*" + guestId);
    }

    // --- 헬퍼 ---------------------------------------------------------------

    private record Fixture(Long orgId, Long propertyId, Long unitId) {
    }

    private Fixture given(String name, short totalUnits) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('Outbox테스트') RETURNING id", Long.class);
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        Long unitId = unitRegistration.register(
                propertyId, name, UnitKind.ENTIRE_PLACE, totalUnits, BigDecimal.valueOf(100000));
        return new Fixture(orgId, propertyId, unitId);
    }

    private static StayPeriod period(int fromOffset, int toOffset) {
        LocalDate base = LocalDate.of(2029, 1, 1);
        return new StayPeriod(base.plusDays(fromOffset), base.plusDays(toOffset));
    }

    private int outboxCount() {
        return count("SELECT COUNT(*) FROM outbox_event");
    }

    private int auditCount() {
        return count("SELECT COUNT(*) FROM audit_log");
    }

    private int outboxCountOf(Long reservationId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?",
                Integer.class, reservationId);
        return c == null ? 0 : c;
    }

    private int auditCountOf(Long reservationId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ?", Integer.class, reservationId);
        return c == null ? 0 : c;
    }

    private List<String> eventTypesOf(Long reservationId) {
        return jdbc.queryForList("""
                SELECT event_type FROM outbox_event
                 WHERE aggregate_type = 'RESERVATION' AND aggregate_id = ?
                 ORDER BY id
                """, String.class, reservationId);
    }

    private List<String> auditActionsOf(Long reservationId) {
        return jdbc.queryForList("""
                SELECT action FROM audit_log
                 WHERE entity_type = 'RESERVATION' AND entity_id = ?
                 ORDER BY id
                """, String.class, reservationId);
    }

    private List<String> payloadsOf(Long reservationId) {
        return jdbc.queryForList(
                "SELECT payload::text FROM outbox_event WHERE aggregate_id = ?",
                String.class, reservationId);
    }

    private List<String> auditValuesOf(Long reservationId) {
        return jdbc.queryForList("""
                SELECT COALESCE(before_value::text, '') || COALESCE(after_value::text, '')
                  FROM audit_log WHERE entity_id = ?
                """, String.class, reservationId);
    }

    private int count(String sql) {
        Integer c = jdbc.queryForObject(sql, Integer.class);
        return c == null ? 0 : c;
    }
}
