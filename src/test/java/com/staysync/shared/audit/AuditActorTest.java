package com.staysync.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.BookingService;
import com.staysync.booking.HoldExpiryJob;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 완료 조건 7. 배치가 남긴 감사 기록의 주체.
 *
 * <p>배치에는 {@code SecurityContext} 가 없다. 주체를 부르는 쪽이 넘기게 만들었다면
 * 어딘가에서 빠뜨려 누가 했는지 알 수 없는 행이 남았을 것이다. {@code AuditRecorder}
 * 가 알아서 판단하므로 배치는 아무것도 하지 않아도 {@code SYSTEM} 이 된다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class AuditActorTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private HoldExpiryJob holdExpiryJob;

    @Autowired
    private AuditRecorder auditRecorder;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("HOLD 만료가 남긴 감사 기록은 actor_kind 가 SYSTEM 이고 actor_id 가 비어 있다")
    void 배치가_남긴_감사는_SYSTEM_이다() {
        Fixture f = given("배치주체");
        Reservation reservation = bookingService.hold(
                f.propertyId(), f.unitId(), period(0, 2), BigDecimal.valueOf(100000), null);

        holdExpiryJob.expireDueHolds(OffsetDateTime.now().plusHours(1));

        Map<String, Object> 기록 = jdbc.queryForMap("""
                SELECT actor_kind, actor_id FROM audit_log
                 WHERE entity_type = 'RESERVATION' AND entity_id = ? AND action = 'HOLD_EXPIRE'
                """, reservation.getId());

        assertThat(기록.get("actor_kind")).isEqualTo("SYSTEM");
        assertThat(기록.get("actor_id"))
                .as("배치에는 주체가 없다. 그래서 컬럼이 nullable 이다")
                .isNull();
    }

    @Test
    @DisplayName("SecurityContext 가 없으면 SYSTEM 으로 남는다")
    void 인증_주체가_없으면_SYSTEM_이_된다() {
        AuditLog 기록 = auditRecorder.record(
                "RESERVATION", 12345L, "TEST_ACTION",
                Map.of("status", "HOLD"), Map.of("status", "EXPIRED"));

        assertThat(기록.getActorKind()).isEqualTo(ActorKind.SYSTEM);
        assertThat(기록.getActorId()).isNull();
    }

    @Test
    void 주체를_지정하면_그대로_남는다() {
        // P3 의 채널 수신은 SecurityContext 로 표현할 수 없는 주체다.
        AuditLog 기록 = auditRecorder.recordAs(
                ActorKind.CHANNEL, null, "RESERVATION", 999L, "CHANNEL_RECEIVE",
                null, Map.of("status", "CONFIRMED"));

        assertThat(기록.getActorKind()).isEqualTo(ActorKind.CHANNEL);
    }

    @Test
    void 전이마다_감사가_쌓인다() {
        Fixture f = given("전이감사");
        Reservation reservation = bookingService.registerManual(
                f.propertyId(), f.unitId(), period(10, 12),
                BigDecimal.valueOf(100000), (short) 2, (short) 0, null);
        bookingService.checkIn(reservation.getId());
        bookingService.checkOut(reservation.getId());

        assertThat(jdbc.queryForList("""
                SELECT action FROM audit_log
                 WHERE entity_type = 'RESERVATION' AND entity_id = ? ORDER BY id
                """, String.class, reservation.getId()))
                .containsExactly("MANUAL_CREATE", "CHECK_IN", "CHECK_OUT");
    }

    private record Fixture(Long propertyId, Long unitId) {
    }

    private Fixture given(String name) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('감사테스트') RETURNING id", Long.class);
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        Long unitId = unitRegistration.register(
                propertyId, name, UnitKind.ENTIRE_PLACE, (short) 1, BigDecimal.valueOf(100000));
        return new Fixture(propertyId, unitId);
    }

    private static StayPeriod period(int fromOffset, int toOffset) {
        LocalDate base = LocalDate.of(2030, 1, 1);
        return new StayPeriod(base.plusDays(fromOffset), base.plusDays(toOffset));
    }
}
