package com.staysync.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.BookingService;
import com.staysync.booking.ChannelBookingCommand;
import com.staysync.booking.ChannelBookingIntake;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.channel.ChannelBookingPoller;
import com.staysync.channel.adapter.ical.IcalAdapter;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.IcalStubServer;
import com.staysync.channel.support.SyncTestBase;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 작업지시-16 의 완료 조건 1·2·3·5·6·7. 금액을 모르는 예약을 0 원으로 세지 않는다.
 *
 * <p><b>미상 예약은 실제 iCal 수신 경로로 만든다</b> — 스텁 발행자 → {@code IcalAdapter}
 * → 폴러 → 수신부. 수기 예약을 UPDATE 로 바꿔 흉내 내지 않는다. 17주차 {@code ReportTest}
 * 가 그렇게 해서 {@code reservation_night} 결함을 못 봤다(4절).
 *
 * <p>{@code ReportTest} 와 같은 컨텍스트(15433, {@code .localdb-test})다.
 */
class UnknownAmountReportTest extends SyncTestBase {

    private static final LocalDate 첫날 = LocalDate.of(2027, 10, 1);
    private static final LocalDate 끝날 = LocalDate.of(2027, 10, 31);

    @Autowired
    private ReportService reports;

    @Autowired
    private BookingService booking;

    @Autowired
    private ChannelBookingIntake intake;

    @Autowired
    private ChannelBookingPoller poller;

    private IcalStubServer feed;

    @BeforeEach
    void 발행자를_띄운다() {
        feed = new IcalStubServer();
    }

    @AfterEach
    void 발행자를_닫는다() {
        feed.close();
    }

    // --- 완료 조건 1 ----------------------------------------------------------

    @Test
    @DisplayName("iCal 로 수신한 예약은 금액이 0 이 아니라 미상(NULL)으로 저장된다")
    void iCal_예약은_금액_미상이다() {
        Fixture f = given("미상수신");
        ChannelConnection c = icalConnection(f);
        feed.publish(calendar(vevent("u-1", "20271005", "20271008")), null);

        assertThat(poller.pollOne(c)).isEqualTo(1);

        assertThat(총액("u-1")).as("발행물에 금액이 없다. 0 을 넣으면 0 원짜리 박이 된다").isNull();
        assertThat(박_금액_미상_수("u-1")).as("박 행도 미상이다").isEqualTo(3);
        assertThat(박_수("u-1")).isEqualTo(3);
    }

    // --- 완료 조건 3·6 --------------------------------------------------------

    @Test
    @DisplayName("미상 박이 있는 기간은 ADR·RevPAR 를 계산하지 않고 미상 건수를 싣는다")
    void 미상이_있으면_단가를_계산하지_않는다() {
        Fixture f = given("미상지표", (short) 2);
        ChannelConnection c = icalConnection(f);
        feed.publish(calendar(vevent("u-2", "20271005", "20271008")), null);   // 3박 미상
        poller.pollOne(c);
        booking.registerManual(f.propertyId(), f.unitId(),                     // 2박 20만원
                new StayPeriod(첫날.plusDays(10), 첫날.plusDays(12)),
                BigDecimal.valueOf(200_000), (short) 2, (short) 0, null);

        ReportMetrics m = reports.of(f.orgId(), f.propertyId(), 첫날, 끝날);

        // 판 것은 5박이 맞다. 얼마에 팔았는지 모르는 것이 3박이다.
        assertThat(m.soldNights()).isEqualTo(5);
        assertThat(m.unknownAmountReservations()).isEqualTo(1);
        assertThat(m.unknownAmountNights()).isEqualTo(3);
        assertThat(m.roomRevenue()).as("확인된 금액의 합").isEqualByComparingTo("200000");
        assertThat(m.adr()).as("20만원 ÷ 5박 = 4만원은 거짓이다").isNull();
        assertThat(m.revPar()).isNull();
        // 점유율은 금액과 무관하다. 2실 × 31일 = 62.
        assertThat(m.occupancyRate()).isEqualByComparingTo("0.0806");

        // 채널 믹스. 미상 채널의 매출은 미상, 비중은 어느 채널도 없다.
        assertThat(m.channelMix()).hasSize(2);
        ReportMetrics.ChannelShare 에어비앤비 = m.channelMix().stream()
                .filter(s -> s.channelCode().equals("AIRBNB_ICAL")).findFirst().orElseThrow();
        ReportMetrics.ChannelShare 직접 = m.channelMix().stream()
                .filter(s -> s.channelCode().equals("DIRECT")).findFirst().orElseThrow();
        assertThat(에어비앤비.reservations()).isEqualTo(1);
        assertThat(에어비앤비.unknownReservations()).isEqualTo(1);
        assertThat(에어비앤비.revenue()).isNull();
        assertThat(에어비앤비.revenueShare()).isNull();
        assertThat(직접.revenue()).isEqualByComparingTo("200000");
        assertThat(직접.revenueShare()).as("전체 매출을 모르므로 100% 가 아니다").isNull();
        assertThat(직접.unknownReservations()).isZero();
    }

    // --- 완료 조건 5 ----------------------------------------------------------

    @Test
    @DisplayName("예약이 없는 기간(분모 0)과 미상 기간은 응답에서 다르다")
    void 분모_0과_미상은_다르다() {
        Fixture f = given("미상구분");
        ChannelConnection c = icalConnection(f);
        feed.publish(calendar(vevent("u-3", "20271005", "20271008")), null);
        poller.pollOne(c);

        ReportMetrics 빈기간 = reports.of(f.orgId(), f.propertyId(), 첫날.plusMonths(3), 끝날.plusMonths(3));
        ReportMetrics 미상기간 = reports.of(f.orgId(), f.propertyId(), 첫날, 끝날);

        // 빈 기간은 0 이 맞다 — 나눌 것이 없다. 미상은 값이 없다 — 나누면 안 된다.
        assertThat(빈기간.adr()).isEqualByComparingTo("0");
        assertThat(빈기간.unknownAmountNights()).isZero();
        assertThat(미상기간.adr()).isNull();
        assertThat(미상기간.unknownAmountNights()).isEqualTo(3);
    }

    // --- 완료 조건 7 ----------------------------------------------------------

    @Test
    @DisplayName("다음 폴링이 미상을 0 으로 되돌리지 않는다 — 같은 발행물도, 날짜가 바뀐 발행물도")
    void 재수신이_미상을_되돌리지_않는다() {
        Fixture f = given("미상재수신");
        ChannelConnection c = icalConnection(f);
        feed.publish(calendar(vevent("u-4", "20271005", "20271008")), null);
        poller.pollOne(c);

        // 같은 내용을 다시 받는다(15분 뒤의 폴링). applyValues 가 금액을 다시 쓰는 자리다.
        poller.pollOne(c);
        assertThat(총액("u-4")).isNull();
        assertThat(박_금액_미상_수("u-4")).isEqualTo(3);

        // 날짜가 바뀌어 수정으로 반영된다. 박 행을 다시 쓰면서 0 이 들어가면 안 된다.
        feed.publish(calendar(vevent("u-4", "20271010", "20271012")), null);
        poller.pollOne(c);
        assertThat(총액("u-4")).isNull();
        assertThat(박_수("u-4")).isEqualTo(2);
        assertThat(박_금액_미상_수("u-4")).isEqualTo(2);
        assertThat(reports.of(f.orgId(), f.propertyId(), 첫날, 끝날).adr()).isNull();
    }

    // --- 완료 조건 2 ----------------------------------------------------------

    @Test
    @DisplayName("V9 가 iCal 예약만 미상으로 바로잡고 수기·위젯·Mock 예약의 금액은 그대로 둔다")
    void 마이그레이션이_iCal_예약만_미상으로_바꾼다() throws IOException {
        Fixture f = given("미상이관", (short) 4);
        ChannelConnection c = icalConnection(f);
        feed.publish(calendar(vevent("u-5", "20271005", "20271008")), null);
        poller.pollOne(c);
        Long 수기 = booking.registerManual(f.propertyId(), f.unitId(),
                new StayPeriod(첫날.plusDays(10), 첫날.plusDays(12)),
                BigDecimal.valueOf(200_000), (short) 2, (short) 0, null).getId();
        // 0 원 수기 예약. 무료 숙박이다. "0 원이면 미상" 규칙을 쓰면 여기서 틀린다.
        Long 무료 = booking.registerManual(f.propertyId(), f.unitId(),
                new StayPeriod(첫날.plusDays(14), 첫날.plusDays(15)),
                BigDecimal.ZERO, (short) 2, (short) 0, null).getId();
        intake.ingest(new ChannelBookingCommand(f.propertyId(), f.unitId(), "MOCK_V9",
                "BK-V9-" + f.unitId(), null, 첫날.plusDays(20), 첫날.plusDays(21),
                BigDecimal.valueOf(100_000), 1, false));

        // 배포 DB 의 적용 전 상태. V1 의 DEFAULT 0 이 iCal 예약에 0 을 넣어 두었다.
        jdbc.update("UPDATE reservation SET total_amount = 0 WHERE channel_booking_id = 'u-5'");
        jdbc.update("UPDATE reservation_night SET price = 0 WHERE reservation_id = "
                + "(SELECT id FROM reservation WHERE channel_booking_id = 'u-5')");
        long 전 = 예약_행수(f);

        V9_데이터문을_실행한다();

        assertThat(예약_행수(f)).as("행 수는 그대로다").isEqualTo(전);
        assertThat(총액("u-5")).as("iCal 예약은 미상이 된다").isNull();
        assertThat(박_금액_미상_수("u-5")).isEqualTo(3);
        assertThat(총액_id(수기)).isEqualByComparingTo("200000");
        assertThat(총액_id(무료)).as("0 원 수기 예약은 0 원 그대로다").isEqualByComparingTo("0");
        assertThat(총액("BK-V9-" + f.unitId())).as("Mock 은 금액을 주는 채널이다")
                .isEqualByComparingTo("100000");
    }

    /**
     * V9 의 UPDATE 문만 다시 돌린다. ALTER 는 기동 때 Flyway 가 이미 적용했다.
     * 문장을 여기 베끼면 마이그레이션 파일과 갈라지므로 파일에서 읽는다.
     */
    private void V9_데이터문을_실행한다() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(
                "/db/migration/postgresql/V9__unknown_amount.sql")) {
            assertThat(in).isNotNull();
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int ran = 0;
            for (String statement : sql.split(";")) {
                String body = statement.lines()
                        .filter(line -> !line.strip().startsWith("--"))
                        .reduce("", (a, b) -> a + "\n" + b).strip();
                if (body.startsWith("UPDATE")) {
                    jdbc.update(body);
                    ran++;
                }
            }
            assertThat(ran).as("V9 의 UPDATE 는 예약과 박 둘이다").isEqualTo(2);
        }
    }

    // --- 픽스처 -----------------------------------------------------------------

    private ChannelConnection icalConnection(Fixture f) {
        ChannelConnection connection = channels.create(
                f.propertyId(), f.orgId(), "AIRBNB_ICAL", AdapterType.ICAL, "에어비앤비",
                Map.of(IcalAdapter.ICAL_URL, feed.url()));
        channels.addMapping(connection.getId(), f.orgId(), f.unitId(), "listing-1", null);
        return connection;
    }

    private static String calendar(String... events) {
        return "BEGIN:VCALENDAR\r\nPRODID:-//Airbnb Inc//Hosting Calendar 1.0//EN\r\nVERSION:2.0\r\n"
                + String.join("", events) + "END:VCALENDAR\r\n";
    }

    /** 게시 리스팅의 예약 모양 그대로({@code SUMMARY:Reserved}). 금액 필드는 없다. */
    private static String vevent(String uid, String start, String end) {
        return "BEGIN:VEVENT\r\nDTSTAMP:20260905T024836Z\r\n"
                + "DTSTART;VALUE=DATE:" + start + "\r\nDTEND;VALUE=DATE:" + end + "\r\n"
                + "SUMMARY:Reserved\r\nUID:" + uid + "\r\nEND:VEVENT\r\n";
    }

    private BigDecimal 총액(String channelBookingId) {
        return jdbc.queryForObject(
                "SELECT total_amount FROM reservation WHERE channel_booking_id = ?",
                BigDecimal.class, channelBookingId);
    }

    private BigDecimal 총액_id(Long id) {
        return jdbc.queryForObject(
                "SELECT total_amount FROM reservation WHERE id = ?", BigDecimal.class, id);
    }

    private int 박_수(String channelBookingId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM reservation_night n JOIN reservation r ON r.id = n.reservation_id
                WHERE r.channel_booking_id = ?
                """, Integer.class, channelBookingId);
    }

    private int 박_금액_미상_수(String channelBookingId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM reservation_night n JOIN reservation r ON r.id = n.reservation_id
                WHERE r.channel_booking_id = ? AND n.price IS NULL
                """, Integer.class, channelBookingId);
    }

    private long 예약_행수(Fixture f) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE property_id = ?", Long.class, f.propertyId());
    }
}
