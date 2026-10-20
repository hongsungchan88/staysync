package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.ChannelBookingCommand;
import com.staysync.booking.ChannelBookingIntake;
import com.staysync.channel.adapter.ical.IcalAdapter;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.ChannelMapping;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.IcalStubServer;
import com.staysync.channel.support.SyncTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 작업지시-24 3절 B. Channex 연결을 해제하고 iCal 로 돌아갈 때 iCal 이 같은 날짜의 Channex 예약을 되찾는다.
 *
 * <p>8.1 의 로컬 재현(예약 4건·충돌 5)이 그대로 테스트다. iCal 쪽은 스텁 발행자 → {@code IcalAdapter} → 폴러의 실제
 * 경로다. 연결 기간의 Channex 수신은 폴러가 만드는 명령 그대로 수신부를 부른다(Channex 넘겨받기는
 * {@code ChannelTakeoverTest} 가 따로 본다).
 *
 * <p>날짜는 오늘 기준이다 — iCal 의 {@code cancelMissing} 이 체크아웃이 지난 예약을 건드리지 않는다.
 */
class IcalTakeBackTest extends SyncTestBase {

    private static final DateTimeFormatter ICAL = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired
    private ChannelBookingPoller poller;

    @Autowired
    private ChannelBookingIntake intake;

    @Autowired
    private ChannelMappingRepository mappings;

    private IcalStubServer feed;

    private final LocalDate inA = LocalDate.now().plusDays(30);
    private final LocalDate outA = inA.plusDays(2);
    private final LocalDate inB = LocalDate.now().plusDays(40);
    private final LocalDate outB = inB.plusDays(3);

    @BeforeEach
    void 발행자를_띄운다() {
        feed = new IcalStubServer();
    }

    @AfterEach
    void 발행자를_닫는다() {
        feed.close();
    }

    @Test
    @DisplayName("해제 뒤 iCal 이 돌아오면 같은 예약을 되찾는다 — 예약 2건·충돌 0, 값 유지, 이후 취소도 그 예약에")
    void 해제_뒤_iCal_이_되찾는다() {
        Fixture f = given("되찾기");
        ChannelConnection ical = icalConnection(f);
        connectWithoutMapping(f, "AIRBNB", AdapterType.CHANNEX, "http://unused");
        feed.publish(calendar(vevent("uid-A", inA, outA)), null);
        poller.pollOne(ical);
        Long a = id("uid-A");

        // 연결: iCal 매핑만 풀고, Channex 가 A 를 넘겨받고 연결 기간에 B 가 Channex 로 새로 온다.
        unmap(ical);
        intake.ingest(channex(f, "chx-A", inA, outA));
        Long b = intake.ingest(channex(f, "chx-B", inB, outB)).reservationId();
        assertThat(code(a)).isEqualTo("AIRBNB");
        long[] 원장전 = 원장(f);

        // 해제: Channex 매핑은 없다(애초에 이 테스트에서 안 붙였다). iCal 매핑을 되살리고 피드에 A·B 둘 다.
        channels.addMapping(ical.getId(), f.orgId(), f.unitId(), "listing-1", null);
        feed.publish(calendar(vevent("uid-A", inA, outA), vevent("uid-B", inB, outB)), null);
        poller.pollOne(ical);

        assertThat(예약수(f)).as("재현에서는 4건이었다").isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM overbooking_conflict WHERE unit_id = ? AND status = 'OPEN'",
                Integer.class, f.unitId())).as("재현에서는 5였다").isZero();
        assertThat(원장(f)).as("같은 밤을 같은 예약이 계속 잡는다").containsExactly(원장전);
        assertThat(id("uid-A")).isEqualTo(a);
        assertThat(id("uid-B")).isEqualTo(b);
        assertThat(code(a)).isEqualTo("AIRBNB_ICAL");
        // iCal 이 주지 않는 값은 덮지 않는다(작업지시-16).
        assertThat(jdbc.queryForMap(
                "SELECT total_amount, adults, children, guest_id FROM reservation WHERE id = ?", b))
                .satisfies(row -> {
                    assertThat((BigDecimal) row.get("total_amount")).isEqualByComparingTo("340000");
                    assertThat(((Number) row.get("adults")).intValue()).isEqualTo(3);
                    assertThat(((Number) row.get("children")).intValue()).isEqualTo(1);
                    assertThat(row.get("guest_id")).isNotNull();
                });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_log WHERE entity_type = 'RESERVATION' AND action = 'CHANNEL_TAKEBACK'
                   AND entity_id IN (?, ?)""", Integer.class, a, b)).isEqualTo(2);

        // 그 뒤 에어비앤비에서 A 가 취소돼 피드에서 빠지면 — 이제 그 예약이 취소된다. 재현에서는 AIRBNB 쪽이 영영 남았다.
        feed.publish(calendar(vevent("uid-B", inB, outB)), null);
        poller.pollOne(ical);

        assertThat(status(a)).isEqualTo("CANCELLED");
        assertThat(status(b)).isEqualTo("CONFIRMED");
        assertThat(booked(f, inA)).as("취소되면 재고가 풀린다").isZero();
    }

    @Test
    @DisplayName("그 판매 단위의 Channex 매핑이 켜져 있으면 iCal 은 되찾지 않는다 — 지금처럼 새 예약 + 충돌")
    void Channex_매핑이_켜져_있으면_되찾지_않는다() {
        Fixture f = given("켜짐");
        ChannelConnection ical = icalConnection(f);
        ChannelConnection channex = connectWithoutMapping(f, "AIRBNB", AdapterType.CHANNEX, "http://unused");
        Long b = intake.ingest(channex(f, "chx-B", inB, outB)).reservationId();
        // 화면·API 는 iCal 과 Channex 를 함께 매핑하지 못하게 막는다(MAPPING_DOUBLE_INTAKE). 이 테스트는 그 상태를
        // 직접 만들어 폴러의 조건 자체를 본다.
        mappings.save(new ChannelMapping(channex.getId(), f.unitId(), "room-1", "rate-1", null));

        // 멱등키(channel_code, channel_booking_id)는 전역이다 — 앞 테스트의 uid-B 와 겹치지 않게 다른 UID.
        feed.publish(calendar(vevent("uid-C", inB, outB)), null);
        poller.pollOne(ical);

        assertThat(code(b)).as("Channex 가 그 예약의 주인이다").isEqualTo("AIRBNB");
        assertThat(예약수(f)).isEqualTo(2);
    }

    // --- 작업지시-27. "모름"은 "바뀜"이 아니다 ---------------------------------------------

    @Test
    @DisplayName("되찾은 예약은 같은 피드로 두 번 더 폴링해도 금액·인원·박 단가가 그대로이고 CHANNEL_REVISION 이 없다 — 작업지시-27 완료 조건 1")
    void 되찾은_예약은_다음_폴링에도_값을_유지한다() {
        Fixture f = given("되찾기-유지");
        String uid = "uid-" + java.util.UUID.randomUUID();
        Long r = 되찾은_예약(f, uid, inA, outA);

        poller.pollOne(icalOf(f));
        poller.pollOne(icalOf(f));

        assertThat(값(r)).containsExactly("340000.00", 3, 1);
        assertThat(박단가(r)).containsExactly("170000.00", "170000.00");
        assertThat(감사(r, "CHANNEL_REVISION")).as("날짜가 같고 나머지는 모름 — 아무것도 안 한 것").isZero();
    }

    @Test
    @DisplayName("금액이 있는 iCal 예약의 날짜가 피드에서 바뀌면 날짜는 옮기고 금액·인원은 유지, 박 단가는 새 박 수로 — 완료 조건 2")
    void 날짜가_바뀌어도_금액과_인원은_유지한다() {
        Fixture f = given("되찾기-이동");
        String uid = "uid-" + java.util.UUID.randomUUID();
        Long r = 되찾은_예약(f, uid, inA, outA);

        // 2박 → 3박(체크아웃 하루 뒤로).
        feed.publish(calendar(vevent(uid, inA, outA.plusDays(1))), null);
        poller.pollOne(icalOf(f));

        assertThat(jdbc.queryForObject("SELECT check_out FROM reservation WHERE id = ?", LocalDate.class, r))
                .isEqualTo(outA.plusDays(1));
        assertThat(값(r)).containsExactly("340000.00", 3, 1);
        assertThat(박단가(r)).as("ReservationNight.split — 나머지는 첫 밤")
                .containsExactly("113333.34", "113333.33", "113333.33");
        assertThat(booked(f, outA)).as("늘어난 밤도 재고를 잡는다").isEqualTo(1);
    }

    @Test
    @DisplayName("금액 NULL·인원 0 인 보통 iCal 예약의 날짜 변경·취소는 전과 같다 — 완료 조건 3")
    void 보통_iCal_예약은_전과_같다() {
        Fixture f = given("보통-iCal");
        ChannelConnection ical = icalConnection(f);
        String uid = "uid-" + java.util.UUID.randomUUID();
        feed.publish(calendar(vevent(uid, inA, outA)), null);
        poller.pollOne(ical);
        Long r = id(uid);
        assertThat(값(r)).containsExactly(null, 0, 0);

        feed.publish(calendar(vevent(uid, inA.plusDays(1), outA.plusDays(1))), null);
        poller.pollOne(ical);
        assertThat(jdbc.queryForObject("SELECT check_in FROM reservation WHERE id = ?", LocalDate.class, r))
                .isEqualTo(inA.plusDays(1));
        assertThat(값(r)).containsExactly(null, 0, 0);
        assertThat(박단가(r)).containsExactly(null, null);
        assertThat(감사(r, "CHANNEL_REVISION")).isEqualTo(1);

        feed.publish(calendar(), null);
        poller.pollOne(ical);
        assertThat(status(r)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("Channex 수정 리비전에 금액이 빠지면 저장된 금액을 유지한다 — 완료 조건 4")
    void Channex_수정에_금액이_없으면_유지한다() {
        Fixture f = given("Channex-금액없음");
        connectWithoutMapping(f, "AIRBNB", AdapterType.CHANNEX, "http://unused");
        String bookingId = "chx-" + java.util.UUID.randomUUID();
        Long r = intake.ingest(channex(f, bookingId, inB, outB)).reservationId();

        intake.ingest(new ChannelBookingCommand(f.propertyId(), f.unitId(), "AIRBNB", bookingId, "Te st",
                inB, outB.plusDays(1), null, 3, 1, 101, false, Set.of("AIRBNB_ICAL")));

        assertThat(jdbc.queryForObject("SELECT check_out FROM reservation WHERE id = ?", LocalDate.class, r))
                .isEqualTo(outB.plusDays(1));
        assertThat(값(r)).containsExactly("340000.00", 3, 1);
        assertThat(jdbc.queryForObject("SELECT revision FROM reservation WHERE id = ?", Integer.class, r))
                .isEqualTo(101);
    }

    /** iCal 예약 → Channex 가 넘겨받음(금액·인원) → 매핑 되돌려 iCal 이 되찾음. 되찾는 첫 폴링까지. */
    private Long 되찾은_예약(Fixture f, String uid, LocalDate in, LocalDate out) {
        ChannelConnection ical = icalConnection(f);
        connectWithoutMapping(f, "AIRBNB", AdapterType.CHANNEX, "http://unused");
        feed.publish(calendar(vevent(uid, in, out)), null);
        poller.pollOne(ical);
        Long r = id(uid);

        unmap(ical);
        intake.ingest(channex(f, "chx-" + java.util.UUID.randomUUID(), in, out));
        channels.addMapping(ical.getId(), f.orgId(), f.unitId(), "listing-1", null);
        poller.pollOne(ical);
        assertThat(code(r)).isEqualTo("AIRBNB_ICAL");
        assertThat(감사(r, "CHANNEL_TAKEBACK")).isEqualTo(1);
        assertThat(값(r)).as("되찾는 순간은 원래 맞았다").containsExactly("340000.00", 3, 1);
        return r;
    }

    private ChannelConnection icalOf(Fixture f) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM channel_connection WHERE property_id = ? AND adapter_type = 'ICAL'",
                Long.class, f.propertyId());
        return channels.get(id, f.orgId());
    }

    /** {금액(소수 둘째 자리 문자열 또는 null), 성인, 아동}. */
    private java.util.List<Object> 값(Long id) {
        return jdbc.queryForObject("SELECT total_amount, adults, children FROM reservation WHERE id = ?",
                (rs, i) -> java.util.Arrays.asList(
                        rs.getBigDecimal(1) == null ? null : rs.getBigDecimal(1).setScale(2).toPlainString(),
                        rs.getInt(2), rs.getInt(3)), id);
    }

    private java.util.List<String> 박단가(Long id) {
        return jdbc.query("SELECT price FROM reservation_night WHERE reservation_id = ? ORDER BY stay_date",
                (rs, i) -> rs.getBigDecimal(1) == null ? null : rs.getBigDecimal(1).setScale(2).toPlainString(), id);
    }

    private int 감사(Long id, String action) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_type = 'RESERVATION' AND entity_id = ? AND action = ?",
                Integer.class, id, action);
    }

    // --- 픽스처 -----------------------------------------------------------------

    private ChannelConnection icalConnection(Fixture f) {
        ChannelConnection connection = channels.create(
                f.propertyId(), f.orgId(), "AIRBNB_ICAL", AdapterType.ICAL, "에어비앤비",
                Map.of(IcalAdapter.ICAL_URL, feed.url()));
        channels.addMapping(connection.getId(), f.orgId(), f.unitId(), "listing-1", null);
        return connection;
    }

    private void unmap(ChannelConnection connection) {
        mappings.findByConnectionIdOrderByIdAsc(connection.getId()).forEach(mappings::delete);
    }

    /** Channex 폴러가 만드는 명령 그대로 — 이름·금액·인원·버전이 있고, 같은 숙소 iCal 코드를 넘겨받는다. */
    private static ChannelBookingCommand channex(Fixture f, String bookingId, LocalDate in, LocalDate out) {
        return new ChannelBookingCommand(f.propertyId(), f.unitId(), "AIRBNB", bookingId, "Te st",
                in, out, BigDecimal.valueOf(340_000), 3, 1, 100, false, Set.of("AIRBNB_ICAL"));
    }

    private static String calendar(String... events) {
        return "BEGIN:VCALENDAR\r\nPRODID:-//Airbnb Inc//Hosting Calendar 1.0//EN\r\nVERSION:2.0\r\n"
                + String.join("", events) + "END:VCALENDAR\r\n";
    }

    private static String vevent(String uid, LocalDate start, LocalDate endExclusive) {
        return "BEGIN:VEVENT\r\nDTSTAMP:20261014T024836Z\r\n"
                + "DTSTART;VALUE=DATE:" + ICAL.format(start) + "\r\nDTEND;VALUE=DATE:" + ICAL.format(endExclusive) + "\r\n"
                + "SUMMARY:Reserved\r\nUID:" + uid + "\r\nEND:VEVENT\r\n";
    }

    private Long id(String channelBookingId) {
        return jdbc.queryForObject(
                "SELECT id FROM reservation WHERE channel_booking_id = ?", Long.class, channelBookingId);
    }

    private String code(Long id) {
        return jdbc.queryForObject("SELECT channel_code FROM reservation WHERE id = ?", String.class, id);
    }

    private String status(Long id) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, id);
    }

    private int 예약수(Fixture f) {
        return jdbc.queryForObject("SELECT count(*) FROM reservation WHERE unit_id = ?", Integer.class, f.unitId());
    }

    /** {원장 행 수, booked_units 합}. */
    private long[] 원장(Fixture f) {
        return jdbc.queryForObject(
                "SELECT count(*), COALESCE(SUM(booked_units), 0) FROM inventory_ledger WHERE unit_id = ?",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}, f.unitId());
    }

    private int booked(Fixture f, LocalDate date) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(booked_units), 0) FROM inventory_ledger WHERE unit_id = ? AND stay_date = ?",
                Integer.class, f.unitId(), date);
    }
}
