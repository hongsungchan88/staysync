package com.staysync.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.staysync.booking.domain.InsufficientInventoryException;
import com.staysync.booking.domain.OverbookingConflict;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.channel.support.SyncTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 작업지시-18 9절 E·F. 초과 예약이 올린 수량이 반납되면 내려오고, 초과가 풀리면 OPEN
 * 충돌이 자동으로 닫힌다. 완료 조건 15·16·17·18·19.
 *
 * <p>초과 예약은 채널 수신 경로({@code ChannelBookingIntake.ingest})로, 해소는 해소
 * API({@code ConflictResolutionService.resolve})로 만든다. 원장은 SQL 로 직접 본다 —
 * {@code total_units} 가 실제 수량인지가 요점이라 화면 값만으로는 모자란다.
 */
class ConflictCleanupTest extends SyncTestBase {

    private static final LocalDate 체크인 = LocalDate.now().plusDays(45);
    private static final LocalDate 체크아웃 = 체크인.plusDays(1);

    @Autowired
    private ChannelBookingIntake intake;

    @Autowired
    private ConflictResolutionService conflictService;

    @Autowired
    private OverbookingConflictRepository conflicts;

    @Autowired
    private PublicBookingService publicBooking;

    // --- 완료 조건 15 -----------------------------------------------------------

    @Test
    @DisplayName("초과 예약 둘 중 하나가 채널에서 취소되면 총수량은 실제 수량, 가용 0, OPEN 충돌 0(자동 닫힘)")
    void 취소로_초과가_풀리면_자동으로_닫힌다() {
        Fixture f = given("뒷정리-취소");
        intake.ingest(command(f, "C-0", 1, false));
        Long 초과 = intake.ingest(command(f, "C-1", 1, false)).reservationId();
        assertThat(원장(f)).containsEntry("total", 1).containsEntry("booked", 2).containsEntry("overbooked", 1);
        Long 카드 = 열린충돌(f).get(0).getId();

        intake.ingest(command(f, "C-1", 2, true));   // 채널의 취소 통지

        Map<String, Integer> 원장 = 원장(f);
        assertThat(원장).as("총수량은 판매 단위의 실제 수량이다 — 부풀린 채 남지 않는다")
                .containsEntry("total", 1).containsEntry("booked", 1).containsEntry("overbooked", 0);
        assertThat(가용(f)).as("남은 정상 예약 하나가 그 방을 쓴다. 하나 더 팔리면 안 된다").isZero();
        assertThat(열린충돌(f)).isEmpty();

        OverbookingConflict 닫힌것 = conflicts.findById(카드).orElseThrow();
        assertThat(닫힌것.getStatus()).isEqualTo(OverbookingConflict.RESOLVED);
        assertThat(닫힌것.getResolution()).as("사람이 닫은 것과 구분된다").isEqualTo(OverbookingConflict.AUTO_CLOSED);
        assertThat(닫힌것.getResolvedBy()).isNull();
        assertThat(닫힌것.getMemo()).contains("자동");
        assertThat(감사_행위자(카드)).as("감사 기록은 시스템 행위자다").isEqualTo("SYSTEM");
        assertThat(초과).isNotNull();
    }

    // --- 완료 조건 16 -----------------------------------------------------------

    @Test
    @DisplayName("UPGRADED 로 해소하면 원래 판매 단위의 총수량이 실제 수량이고 가용 0 이다")
    void 업그레이드_해소_뒤_원래_단위가_부풀지_않는다() {
        Fixture f = given("뒷정리-업그레이드");
        Long 별채 = unitRegistration.register(f.propertyId(), "별채",
                com.staysync.property.domain.UnitKind.ENTIRE_PLACE, (short) 1, BigDecimal.valueOf(100_000));
        intake.ingest(command(f, "U-0", 1, false));
        Long 초과 = intake.ingest(command(f, "U-1", 1, false)).reservationId();
        Long 카드 = 열린충돌(f).get(0).getId();

        conflictService.resolve(카드, f.orgId(), 사용자(f),
                OverbookingConflict.UPGRADED, 초과, 별채, "별채로");

        assertThat(원장(f)).containsEntry("total", 1).containsEntry("booked", 1).containsEntry("overbooked", 0);
        assertThat(가용(f)).isZero();
        assertThat(원장(별채, 체크인)).containsEntry("booked", 1).containsEntry("overbooked", 0);
        assertThat(열린충돌(f)).isEmpty();
    }

    // --- 완료 조건 17 -----------------------------------------------------------

    @Test
    @DisplayName("초과 예약 셋 중 하나가 취소되면 아직 초과라 OPEN 을 유지하고 예약 목록만 둘로 갱신한다")
    void 아직_초과면_열린_채_목록만_갱신한다() {
        Fixture f = given("뒷정리-셋");
        Long 정상 = intake.ingest(command(f, "T-0", 1, false)).reservationId();
        Long 초과1 = intake.ingest(command(f, "T-1", 1, false)).reservationId();
        Long 초과2 = intake.ingest(command(f, "T-2", 1, false)).reservationId();
        assertThat(원장(f)).containsEntry("overbooked", 2);

        intake.ingest(command(f, "T-2", 2, true));

        assertThat(원장(f)).containsEntry("total", 1).containsEntry("booked", 2).containsEntry("overbooked", 1);
        List<OverbookingConflict> open = 열린충돌(f);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getReservationIds()).containsExactlyInAnyOrder(정상, 초과1);
        assertThat(open.get(0).getReservationIds()).doesNotContain(초과2);
    }

    // --- 완료 조건 18 -----------------------------------------------------------

    @Test
    @DisplayName("ABSORBED 로 두 예약이 남은 날 — 가용 0 이고 위젯도 ARI 도 그 날을 팔지 않는다")
    void 흡수한_날은_가용이_0_이다() {
        Fixture f = given("뒷정리-흡수");
        intake.ingest(command(f, "A-0", 1, false));
        Long 초과 = intake.ingest(command(f, "A-1", 1, false)).reservationId();
        Long 카드 = 열린충돌(f).get(0).getId();

        conflictService.resolve(카드, f.orgId(), 사용자(f),
                OverbookingConflict.ABSORBED, 초과, null, "옆 방 있음");

        // 재고를 옮기지 않는 해소라 초과분은 남는다. 그 날을 하나 더 팔면 셋이 된다.
        assertThat(원장(f)).containsEntry("total", 1).containsEntry("booked", 2).containsEntry("overbooked", 1);
        // 채널(ARI)·캘린더·위젯이 전부 읽는 값. 음수가 나가면 채널이 그것을 0 으로 읽지 않는다.
        assertThat(가용(f)).isZero();
        assertThat(publicBooking.availability(f.propertyId(), 체크인, 체크아웃)
                .units().get(0).days().get(0).available())
                .as("위젯 가용")
                .isZero();
        assertThatThrownBy(() -> publicBooking.hold(f.propertyId(), f.unitId(),
                new StayPeriod(체크인, 체크아웃), null, (short) 2, (short) 0,
                "손님", null, "g@example.com"))
                .as("위젯이 그 날을 팔지 않는다 — 가용 0 을 읽고 원장에 닿기 전에 거절한다")
                .isInstanceOf(UnavailableDateException.class);
    }

    // --- 완료 조건 19 -----------------------------------------------------------

    @Test
    @DisplayName("초과 예약 경로가 아닌 곳에서는 여전히 DB 가 초과 판매를 거부한다")
    void 제약이_살아_있다() {
        Fixture f = given("뒷정리-제약");
        intake.ingest(command(f, "K-0", 1, false));

        // 로직 결함을 흉내 낸다 — overbooked 를 안 올리고 수량을 넘긴다. forceBook 만 올릴 수 있다.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE inventory_ledger SET booked_units = booked_units + 1 WHERE unit_id = ? AND stay_date = ?",
                f.unitId(), 체크인))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_");
        // 초과분을 거짓으로 적어도 등식이 막는다.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE inventory_ledger SET overbooked_units = 1 WHERE unit_id = ? AND stay_date = ?",
                f.unitId(), 체크인))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 앱 경로도 그대로 거절한다.
        assertThatThrownBy(() -> inventory.reserve(f.unitId(), new StayPeriod(체크인, 체크아웃), 1))
                .isInstanceOf(InsufficientInventoryException.class);
        assertThat(원장(f)).containsEntry("booked", 1).containsEntry("overbooked", 0);
    }

    // --- 픽스처 -----------------------------------------------------------------

    private ChannelBookingCommand command(Fixture f, String bookingId, int revision, boolean cancellation) {
        return new ChannelBookingCommand(f.propertyId(), f.unitId(), "MOCK_CLEANUP",
                bookingId + "-" + f.unitId(), null, 체크인, 체크아웃,
                BigDecimal.valueOf(100_000), revision, cancellation);
    }

    private List<OverbookingConflict> 열린충돌(Fixture f) {
        return conflicts.findOpenIn(f.propertyId(), 체크인, 체크인);
    }

    private int 가용(Fixture f) {
        return inventory.availabilityByDate(f.unitId(), 체크인, 체크인,
                inventory.capacityOf(f.unitId())).get(0).available();
    }

    private Map<String, Integer> 원장(Fixture f) {
        return 원장(f.unitId(), 체크인);
    }

    private Map<String, Integer> 원장(Long unitId, LocalDate date) {
        return jdbc.queryForObject("""
                SELECT total_units, booked_units, held_units, overbooked_units
                FROM inventory_ledger WHERE unit_id = ? AND stay_date = ?
                """, (rs, row) -> Map.of(
                        "total", rs.getInt(1), "booked", rs.getInt(2),
                        "held", rs.getInt(3), "overbooked", rs.getInt(4)),
                unitId, date);
    }

    private String 감사_행위자(Long conflictId) {
        return jdbc.queryForObject("""
                SELECT actor_kind FROM audit_log
                WHERE entity_type = 'OVERBOOKING_CONFLICT' AND entity_id = ? AND action = 'CONFLICT_RESOLVE'
                ORDER BY id DESC LIMIT 1
                """, String.class, conflictId);
    }

    private Long 사용자(Fixture f) {
        return jdbc.queryForObject("""
                INSERT INTO user_account (org_id, email, password_hash, display_name, role)
                VALUES (?, ?, 'x', '운영자', 'OWNER') RETURNING id
                """, Long.class, f.orgId(), "cleanup-" + f.orgId() + "@example.com");
    }
}
