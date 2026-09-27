package com.staysync.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.domain.Reservation;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 작업지시-21 A·B. iCal 예약을 Channex 가 넘겨준 같은 예약으로 갈아 끼운다.
 *
 * <p>잇는 조건은 "같은 판매 단위 + 같은 체크인·체크아웃"이다(5절 1번). 핵심 불변식은 <b>재고 원장이 움직이지
 * 않는다</b>는 것 — 원장 행 수와 {@code booked_units} 합을 전후로 비교한다(4절).
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class ChannelTakeoverTest {

    private static final LocalDate 체크인 = LocalDate.of(2027, 10, 1);
    private static final LocalDate 체크아웃 = LocalDate.of(2027, 10, 3);
    private static final Set<String> ICAL = Set.of("AIRBNB_ICAL");

    @Autowired
    private ChannelBookingIntake intake;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private BookingService booking;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("같은 날짜의 iCal 예약을 넘겨받는다 — id 그대로, 금액·이름·인원 채움, 원장 불변, 감사 행")
    void 같은_날짜면_넘겨받는다() {
        Fixture f = given("넘겨받기");
        Long icalId = intake.ingest(ical(f, "uid-1@airbnb.com", 체크인, 체크아웃)).reservationId();
        long[] 원장전 = 원장(f);

        ChannelBookingResult result = intake.ingest(channex(f, "chx-1", 체크인, 체크아웃, 100, false, ICAL));

        assertThat(result.outcome()).isEqualTo(ChannelBookingResult.Outcome.UPDATED);
        assertThat(result.reservationId()).isEqualTo(icalId);
        assertThat(예약수(f)).isEqualTo(1);
        Reservation r = reservations.findById(icalId).orElseThrow();
        assertThat(r.getChannelCode()).isEqualTo("AIRBNB");
        assertThat(r.getChannelBookingId()).isEqualTo("chx-1");
        assertThat(r.getTotalAmount()).isEqualByComparingTo("340000");
        assertThat(r.getAdults()).isEqualTo((short) 3);
        assertThat(r.getChildren()).isEqualTo((short) 1);
        assertThat(r.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT name FROM guest WHERE id = ?", String.class, r.getGuestId()))
                .isEqualTo("Te st");
        assertThat(원장(f)).as("같은 밤을 같은 예약이 계속 잡는다").containsExactly(원장전);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM overbooking_conflict WHERE unit_id = ?", Integer.class, f.unitId()))
                .isZero();
        assertThat(jdbc.queryForList(
                "SELECT price FROM reservation_night WHERE reservation_id = ? ORDER BY stay_date",
                BigDecimal.class, icalId))
                .as("금액이 생겼으니 박당 단가도 생긴다 — 리포트가 읽는다")
                .hasSize(2)
                .allSatisfy(price -> assertThat(price).isEqualByComparingTo("170000"));
        assertThat(jdbc.queryForObject(
                "SELECT after_value ->> 'channelCode' FROM audit_log "
                        + "WHERE entity_type = 'RESERVATION' AND entity_id = ? AND action = 'CHANNEL_TAKEOVER'",
                String.class, icalId)).isEqualTo("AIRBNB");
    }

    @Test
    @DisplayName("하루라도 다르면 넘겨받지 않는다 — 새 예약과 충돌 카드")
    void 날짜가_다르면_충돌로_간다() {
        Fixture f = given("날짜어긋남");
        Long icalId = intake.ingest(ical(f, "uid-2@airbnb.com", 체크인, 체크아웃)).reservationId();

        ChannelBookingResult result = intake.ingest(
                channex(f, "chx-2", 체크인, 체크아웃.plusDays(1), 100, false, ICAL));

        assertThat(result.outcome()).isEqualTo(ChannelBookingResult.Outcome.CONFLICT);
        assertThat(result.reservationId()).isNotEqualTo(icalId);
        assertThat(reservations.findById(icalId).orElseThrow().getChannelCode()).isEqualTo("AIRBNB_ICAL");
        assertThat(예약수(f)).isEqualTo(2);
    }

    @Test
    @DisplayName("넘겨받을 코드가 없으면(Channex 가 아닌 채널) 같은 날짜여도 넘겨받지 않는다")
    void 넘겨받을_코드가_없으면_넘겨받지_않는다() {
        Fixture f = given("코드없음");
        intake.ingest(ical(f, "uid-3@airbnb.com", 체크인, 체크아웃));

        ChannelBookingResult result = intake.ingest(channex(f, "chx-3", 체크인, 체크아웃, 100, false, Set.of()));

        assertThat(result.outcome()).isEqualTo(ChannelBookingResult.Outcome.CONFLICT);
    }

    @Test
    @DisplayName("넘겨받은 예약은 이후 Channex 의 변경·취소를 따라간다")
    void 넘겨받은_뒤_변경과_취소를_따라간다() {
        Fixture f = given("넘겨받은뒤");
        Long id = intake.ingest(ical(f, "uid-4@airbnb.com", 체크인, 체크아웃)).reservationId();
        intake.ingest(channex(f, "chx-4", 체크인, 체크아웃, 100, false, ICAL));

        ChannelBookingResult modified = intake.ingest(
                channex(f, "chx-4", 체크인, 체크아웃.plusDays(1), 200, false, ICAL));
        assertThat(modified.outcome()).isEqualTo(ChannelBookingResult.Outcome.UPDATED);
        assertThat(modified.reservationId()).isEqualTo(id);
        assertThat(reservations.findById(id).orElseThrow().getPeriod().checkOut())
                .isEqualTo(체크아웃.plusDays(1));

        ChannelBookingResult cancelled = intake.ingest(
                channex(f, "chx-4", 체크인, 체크아웃.plusDays(1), 300, true, ICAL));
        assertThat(cancelled.outcome()).isEqualTo(ChannelBookingResult.Outcome.CANCELLED);
        assertThat(reservations.findById(id).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
        assertThat(원장(f)[1]).as("취소되면 재고가 풀린다").isZero();
    }

    @Test
    @DisplayName("취소된 iCal 예약은 넘겨받지 않는다")
    void 취소된_예약은_넘겨받지_않는다() {
        Fixture f = given("취소된것");
        Long icalId = intake.ingest(ical(f, "uid-5@airbnb.com", 체크인, 체크아웃)).reservationId();
        booking.cancel(icalId);

        ChannelBookingResult result = intake.ingest(channex(f, "chx-5", 체크인, 체크아웃, 100, false, ICAL));

        assertThat(result.outcome()).isEqualTo(ChannelBookingResult.Outcome.CREATED);
        assertThat(result.reservationId()).isNotEqualTo(icalId);
    }

    @Test
    @DisplayName("투숙 중인 iCal 예약도 넘겨받고 상태는 그대로다")
    void 투숙중인_예약도_넘겨받는다() {
        Fixture f = given("투숙중");
        Long icalId = intake.ingest(ical(f, "uid-6@airbnb.com", 체크인, 체크아웃)).reservationId();
        booking.checkIn(icalId);

        ChannelBookingResult result = intake.ingest(channex(f, "chx-6", 체크인, 체크아웃, 100, false, ICAL));

        assertThat(result.reservationId()).isEqualTo(icalId);
        assertThat(reservations.findById(icalId).orElseThrow().getStatus().name()).isEqualTo("CHECKED_IN");
    }

    @Test
    @DisplayName("채널이 준 인원이 저장된다 — 새 예약에도(확인-11 4절 ③)")
    void 채널_예약의_인원이_저장된다() {
        Fixture f = given("인원");

        Long id = intake.ingest(channex(f, "chx-7", 체크인, 체크아웃, 100, false, Set.of())).reservationId();

        Reservation r = reservations.findById(id).orElseThrow();
        assertThat(r.getAdults()).isEqualTo((short) 3);
        assertThat(r.getChildren()).isEqualTo((short) 1);
    }

    // --- 도우미 --------------------------------------------------------------

    /** iCal 수신이 넘기는 모양 — 이름·금액·인원이 없고 버전도 없다(IcalAdapter). */
    private ChannelBookingCommand ical(Fixture f, String uid, LocalDate in, LocalDate out) {
        return new ChannelBookingCommand(f.propertyId(), f.unitId(), "AIRBNB_ICAL", uid, null,
                in, out, null, 0, 0, null, false, Set.of());
    }

    private ChannelBookingCommand channex(Fixture f, String bookingId, LocalDate in, LocalDate out,
                                          int revision, boolean cancellation, Set<String> supersedes) {
        return new ChannelBookingCommand(f.propertyId(), f.unitId(), "AIRBNB", bookingId, "Te st",
                in, out, BigDecimal.valueOf(340_000), 3, 1, revision, cancellation, supersedes);
    }

    /** {원장 행 수, booked_units 합}. */
    private long[] 원장(Fixture f) {
        return jdbc.queryForObject(
                "SELECT count(*), COALESCE(SUM(booked_units), 0) FROM inventory_ledger WHERE unit_id = ?",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, f.unitId());
    }

    private int 예약수(Fixture f) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE unit_id = ?", Integer.class, f.unitId());
    }

    private Fixture given(String name) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES (?) RETURNING id", Long.class, name);
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        Long unitId = unitRegistration.register(propertyId, name + " 객실", UnitKind.ENTIRE_PLACE,
                (short) 1, BigDecimal.valueOf(100_000));
        return new Fixture(propertyId, unitId);
    }

    private record Fixture(Long propertyId, Long unitId) {
    }
}
