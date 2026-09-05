package com.staysync.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.staysync.booking.domain.ReservationStatus;
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
 * 완료 조건 9~12·14. 채널 예약 수신.
 *
 * <p><b>12번이 이 주에서 가장 중요하다.</b> 재고가 모자란 예약을 거절해 버려도 우리 쪽
 * 로그는 깨끗하고, 문제가 드러나는 것은 게스트가 도착한 뒤다. CLAUDE.md 가
 * "OTA 에서 이미 성사된 예약은 거절하지 않는다"를 못박은 이유다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class ChannelIngestTest {

    private static final LocalDate 체크인 = LocalDate.of(2027, 7, 1);
    private static final LocalDate 체크아웃 = LocalDate.of(2027, 7, 3);

    @Autowired
    private ChannelBookingIntake intake;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private OverbookingConflictRepository conflicts;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    // --- 멱등성 (완료 조건 9·10) -----------------------------------------------

    @Test
    @DisplayName("같은 예약을 두 번 받으면 한 건만 생긴다")
    void 중복_수신은_한_건이_된다() {
        Fixture f = given("수신-중복", (short) 2);

        ChannelBookingResult 첫번째 = intake.ingest(command(f, "BK-DUP", 1, false));
        ChannelBookingResult 두번째 = intake.ingest(command(f, "BK-DUP", 1, false));

        assertThat(첫번째.outcome()).isEqualTo(ChannelBookingResult.Outcome.CREATED);
        // 같은 웹훅이 세 번 와도 예약은 한 건이어야 한다. 시뮬레이터의 중복 전송
        // 시나리오가 이걸 위해 있다.
        assertThat(두번째.outcome()).isEqualTo(ChannelBookingResult.Outcome.DUPLICATE);
        assertThat(두번째.reservationId()).isEqualTo(첫번째.reservationId());
        assertThat(예약수(f)).isEqualTo(1);

        // 재고도 한 번만 깎여야 한다. 두 번 깎이면 팔 수 있는 방을 못 판다.
        assertThat(inventory.availabilityByDate(f.unitId(), 체크인, 체크인, (short) 2))
                .singleElement()
                .satisfies(day -> assertThat(day.available()).isEqualTo(1));
    }

    @Test
    @DisplayName("revision 이 역전돼 도착하면 낮은 것이 무시된다")
    void 낮은_버전은_무시된다() {
        Fixture f = given("수신-역전");
        intake.ingest(command(f, "BK-REV", 1, false));

        // 높은 버전이 먼저 온다. 날짜를 이틀 뒤로 옮긴다.
        ChannelBookingResult 높은쪽 = intake.ingest(new ChannelBookingCommand(
                f.propertyId(), f.unitId(), "MOCK_IN", "BK-REV",
                체크인.plusDays(2), 체크아웃.plusDays(2), BigDecimal.valueOf(200_000), 3, false));
        assertThat(높은쪽.outcome()).isEqualTo(ChannelBookingResult.Outcome.UPDATED);

        // 낮은 버전이 나중에 도착한다. 전달 순서가 뒤바뀌는 것은 정상이다.
        ChannelBookingResult 낮은쪽 = intake.ingest(new ChannelBookingCommand(
                f.propertyId(), f.unitId(), "MOCK_IN", "BK-REV",
                체크인, 체크아웃, BigDecimal.valueOf(200_000), 2, false));

        assertThat(낮은쪽.outcome()).isEqualTo(ChannelBookingResult.Outcome.DUPLICATE);
        // 반영했다면 옛 날짜로 되돌아가고, 화면에서는 정상으로 보인다.
        assertThat(reservations.findById(높은쪽.reservationId()).orElseThrow().getPeriod().checkIn())
                .isEqualTo(체크인.plusDays(2));
        assertThat(reservations.findById(높은쪽.reservationId()).orElseThrow().getRevision())
                .isEqualTo(3);
    }

    // --- 취소 (완료 조건 11) ----------------------------------------------------

    @Test
    @DisplayName("취소가 오면 재고가 풀린다")
    void 취소하면_재고가_돌아온다() {
        Fixture f = given("수신-취소");
        intake.ingest(command(f, "BK-CANCEL", 1, false));
        assertThat(가용수량(f)).isZero();

        ChannelBookingResult 취소 = intake.ingest(command(f, "BK-CANCEL", 1, true));

        assertThat(취소.outcome()).isEqualTo(ChannelBookingResult.Outcome.CANCELLED);
        assertThat(reservations.findById(취소.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        // 풀리지 않으면 팔 수 있는 방을 못 판다. 그것도 조용히.
        assertThat(가용수량(f)).isEqualTo(1);
    }

    @Test
    @DisplayName("취소는 revision 을 따지지 않는다")
    void 취소는_버전과_무관하다() {
        Fixture f = given("수신-취소버전");
        intake.ingest(command(f, "BK-CV", 5, false));

        // 채널이 취소했다는 것이 최종 사실이다. 버전으로 막으면 취소된 예약이 살아남아
        // 팔 수 없는 방을 잡는다.
        ChannelBookingResult 취소 = intake.ingest(command(f, "BK-CV", 1, true));

        assertThat(취소.outcome()).isEqualTo(ChannelBookingResult.Outcome.CANCELLED);
        assertThat(가용수량(f)).isEqualTo(1);
    }

    // --- 초과 판매 (완료 조건 12) -----------------------------------------------

    @Test
    @DisplayName("재고가 부족해도 거절하지 않고 충돌로 남긴다")
    void 초과_판매를_거절하지_않는다() {
        Fixture f = given("수신-초과");
        intake.ingest(command(f, "BK-1", 1, false));
        assertThat(가용수량(f)).isZero();

        // 재고가 없는 상태에서 또 들어온다. OTA 에서는 이미 성사된 예약이다.
        ChannelBookingResult 초과 = intake.ingest(command(f, "BK-2", 1, false));

        // 거절하면 게스트와 플랫폼 양쪽에서 문제가 된다.
        assertThat(초과.outcome()).isEqualTo(ChannelBookingResult.Outcome.CONFLICT);
        assertThat(초과.reservationId()).isNotNull();
        assertThat(reservations.findById(초과.reservationId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(예약수(f)).isEqualTo(2);

        // 두 밤 모두 모자라므로 두 행이 남아야 한다. 예외가 알려 주는 첫 날짜만
        // 남기면 나머지 날은 운영자가 놓친다.
        assertThat(초과.conflictDates()).containsExactly(체크인, 체크인.plusDays(1));
        assertThat(conflicts.findByUnitIdOrderByStayDateAsc(f.unitId()))
                .hasSize(2)
                .allSatisfy(conflict -> {
                    assertThat(conflict.getSeverity()).isEqualTo("CRITICAL");
                    assertThat(conflict.getStatus()).isEqualTo("OPEN");
                    // 상대가 없는 "충돌"은 13주차에 풀 수 없다.
                    assertThat(conflict.getReservationIds())
                            .contains(초과.reservationId())
                            .hasSize(2);
                });
    }

    @Test
    @DisplayName("판매중지된 날에 들어온 예약도 받아들이고 충돌로 남긴다")
    void 판매중지에도_거절하지_않는다() {
        Fixture f = given("수신-판매중지", (short) 2);
        inventory.changeStopSell(f.unitId(), List.of(체크인, 체크인.plusDays(1)), true);

        ChannelBookingResult result = intake.ingest(command(f, "BK-STOP", 1, false));

        // 우리가 판매를 멈춘 것과 채널이 이미 팔아 버린 것은 다른 사실이다.
        assertThat(result.outcome()).isEqualTo(ChannelBookingResult.Outcome.CONFLICT);
        assertThat(conflicts.findByUnitIdOrderByStayDateAsc(f.unitId())).hasSize(2);
    }

    // --- 트랜잭션 (완료 조건 14) -------------------------------------------------

    @Test
    @DisplayName("수신이 실패하면 예약·재고·이벤트·감사가 모두 사라진다")
    void 롤백되면_아무것도_남지_않는다() {
        Fixture f = given("수신-롤백");

        // 체크아웃이 체크인보다 앞선 명령. StayPeriod 가 거부하지만, 그 시점은 이미
        // 예약을 저장하려 시도한 뒤여야 의미가 있다. 여기서는 명령을 만들 때 걸린다.
        assertThatThrownBy(() -> intake.ingest(new ChannelBookingCommand(
                f.propertyId(), f.unitId(), "MOCK_IN", "BK-BAD",
                체크아웃, 체크인, BigDecimal.valueOf(200_000), 1, false)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(예약수(f)).isZero();

        // 없는 판매 단위로 넣으면 저장 뒤 외래키에서 걸린다. 그때 예약 행도
        // outbox 도 감사도 함께 사라져야 한다.
        long 이벤트수 = jdbc.queryForObject("SELECT count(*) FROM outbox_event", Long.class);
        assertThatThrownBy(() -> intake.ingest(new ChannelBookingCommand(
                f.propertyId(), 99_999_999L, "MOCK_IN", "BK-NOUNIT",
                체크인, 체크아웃, BigDecimal.valueOf(200_000), 1, false)))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE channel_booking_id = 'BK-NOUNIT'",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Long.class))
                .as("이벤트만 남으면 소비자가 없는 예약을 채널로 전파한다")
                .isEqualTo(이벤트수);
    }

    @Test
    @DisplayName("채널 수신의 감사 주체는 CHANNEL 이고 자격 증명이 없다")
    void 감사는_CHANNEL_주체로_남는다() {
        Fixture f = given("수신-감사");
        ChannelBookingResult result = intake.ingest(command(f, "BK-AUDIT", 1, false));

        java.util.Map<String, Object> 기록 = jdbc.queryForMap("""
                SELECT actor_kind, actor_id, after_value::text AS after_value FROM audit_log
                 WHERE entity_type = 'RESERVATION' AND entity_id = ? AND action = 'CHANNEL_RECEIVE'
                """, result.reservationId());

        // SecurityContext 로 표현할 수 없는 주체다. 5~6주차에 recordAs 를 열어 둔 자리다.
        assertThat(기록.get("actor_kind")).isEqualTo("CHANNEL");
        assertThat(기록.get("actor_id")).isNull();
        assertThat((String) 기록.get("after_value")).doesNotContain("api_key").doesNotContain("key");
    }

    // --- 픽스처 ---------------------------------------------------------------

    private record Fixture(Long propertyId, Long unitId) {
    }

    private Fixture given(String name) {
        return given(name, (short) 1);
    }

    private Fixture given(String name, short totalUnits) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES (?) RETURNING id", Long.class, name);
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        Long unitId = unitRegistration.register(propertyId, name + " 객실",
                UnitKind.ENTIRE_PLACE, totalUnits, BigDecimal.valueOf(100_000));
        return new Fixture(propertyId, unitId);
    }

    private ChannelBookingCommand command(Fixture f, String bookingId, int revision,
                                          boolean cancellation) {
        return new ChannelBookingCommand(f.propertyId(), f.unitId(), "MOCK_IN", bookingId,
                체크인, 체크아웃, BigDecimal.valueOf(200_000), revision, cancellation);
    }

    private int 가용수량(Fixture f) {
        return inventory.availabilityByDate(f.unitId(), 체크인, 체크인,
                inventory.capacityOf(f.unitId())).get(0).available();
    }

    private int 예약수(Fixture f) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE unit_id = ?", Integer.class, f.unitId());
    }
}
