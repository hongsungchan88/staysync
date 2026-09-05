package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.channel.adapter.ical.IcalAdapter;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.IcalStubServer;
import com.staysync.channel.support.SyncTestBase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * <b>완료 조건 4·5·6·7.</b> iCal 수신.
 *
 * <p>어댑터도 파서도 수신부도 진짜를 쓴다. 발행자만 스텁이다 — 실제 에어비앤비는
 * 미게시 상태라 발행물을 마음대로 바꿔 줄 수 없어서(조사-02 2절) 수정·취소·급감을
 * 만들 수 없다. 픽스처 파싱은 {@code IcalParserTest} 가 실제 발행물로 확인한다.
 */
class IcalFeedTest extends SyncTestBase {

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

    @Test
    @DisplayName("발행물의 VEVENT 가 예약으로 들어오고 DTEND 가 체크아웃일이 된다")
    void 발행물이_예약이_된다() {
        Fixture f = given("iCal 수신");
        ChannelConnection connection = icalConnection(f);
        feed.publish(calendar(vevent("uid-1", "20261001", "20261004")), null);

        assertThat(poller.pollOne(connection)).isEqualTo(1);

        assertThat(체크인과_체크아웃("uid-1"))
                .as("DTEND 20261004 는 그대로 체크아웃일이다. 하루를 빼면 안 된다")
                .containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 4));
    }

    @Test
    @DisplayName("같은 UID 의 날짜가 바뀌면 수정으로 처리된다. 해시 크기를 비교하지 않는다")
    void 값이_달라지면_수정한다() {
        // 계획서 13.4 는 hashOf(start, end, summary) 를 revision 자리에 넣었는데,
        // 수신부가 크기를 비교하므로 새 해시가 옛 해시보다 작으면 수정이 무시된다.
        // 해시에는 순서가 없어서 그 일이 절반의 확률로 일어나고, 로그는 깨끗하다.
        Fixture f = given("iCal 수정");
        ChannelConnection connection = icalConnection(f);

        feed.publish(calendar(vevent("uid-2", "20261101", "20261104")), null);
        poller.pollOne(connection);

        feed.publish(calendar(vevent("uid-2", "20261105", "20261108")), null);
        poller.pollOne(connection);

        assertThat(예약_건수("uid-2")).as("같은 UID 는 한 건이어야 한다").isEqualTo(1);
        assertThat(체크인과_체크아웃("uid-2"))
                .containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 8));
    }

    @Test
    @DisplayName("UID 가 발행물에서 사라지면 취소로 처리된다")
    void 사라진_UID_는_취소다() {
        // iCal 에는 취소 통지가 없다. VEVENT 가 사라지는 것이 취소다.
        Fixture f = given("iCal 취소");
        ChannelConnection connection = icalConnection(f);

        feed.publish(calendar(vevent("uid-3", "20261201", "20261203")), null);
        poller.pollOne(connection);
        assertThat(상태("uid-3")).isEqualTo("CONFIRMED");

        feed.publish(calendar(), null);
        poller.pollOne(connection);

        assertThat(상태("uid-3")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("일정 수가 직전 대비 절반 이하로 줄면 취소를 보류하고 알린다")
    void 대량_소실이면_취소를_보류한다() {
        // 파싱 실패나 발행자의 일시적 오류로 전체 예약이 취소되는 사고를 막는다.
        // 그 사고는 되돌릴 수 없다 — 취소가 다시 채널로 전파된다.
        Fixture f = given("iCal 급감", (short) 10);
        ChannelConnection connection = icalConnection(f);

        feed.publish(calendar(
                vevent("s-1", "20270101", "20270103"),
                vevent("s-2", "20270105", "20270107"),
                vevent("s-3", "20270109", "20270111"),
                vevent("s-4", "20270113", "20270115"),
                vevent("s-5", "20270117", "20270119"),
                vevent("s-6", "20270121", "20270123")), null);
        assertThat(poller.pollOne(connection)).isEqualTo(6);

        // 여섯 건이 한 건으로 줄었다. 절반 이하다.
        feed.publish(calendar(vevent("s-1", "20270101", "20270103")), null);
        poller.pollOne(connection);

        assertThat(List.of("s-2", "s-3", "s-4", "s-5", "s-6"))
                .as("보류했으므로 하나도 취소되지 않았다")
                .allSatisfy(uid -> assertThat(상태(uid)).isEqualTo("CONFIRMED"));
        assertThat(직전_일정수(connection))
                .as("기준값을 갱신하면 다음 주기에 방어가 걸리지 않는다")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("ETag 가 같으면 본문을 받지 않고 파싱도 하지 않는다")
    void ETag_가_같으면_파싱하지_않는다() {
        Fixture f = given("iCal ETag");
        ChannelConnection connection = icalConnection(f);
        feed.publish(calendar(vevent("uid-4", "20270201", "20270203")), "\"v1\"");

        poller.pollOne(connection);
        assertThat(feed.bodyServed()).isEqualTo(1);

        poller.pollOne(connection);

        assertThat(feed.bodyServed()).as("두 번째는 304 라 본문이 오지 않는다").isEqualTo(1);
        assertThat(feed.receivedIfNoneMatch().get(1))
                .as("직전 ETag 를 실어 보낸다")
                .isEqualTo("\"v1\"");
        assertThat(상태("uid-4"))
                .as("304 를 빈 목록으로 다루면 이 예약이 취소된다")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("발행자가 404 로 답해도 기존 예약을 취소하지 않는다")
    void 발행자가_실패하면_아무것도_바꾸지_않는다() {
        Fixture f = given("iCal 실패");
        ChannelConnection connection = icalConnection(f);
        feed.publish(calendar(vevent("uid-5", "20270301", "20270303")), null);
        poller.pollOne(connection);

        feed.respondWith(404);
        assertThat(poller.pollOne(connection)).isZero();

        assertThat(상태("uid-5")).isEqualTo("CONFIRMED");
    }

    // --- 픽스처 -----------------------------------------------------------------

    private ChannelConnection icalConnection(Fixture f) {
        ChannelConnection connection = channels.create(
                f.propertyId(), f.orgId(), "AIRBNB_ICAL", AdapterType.ICAL, "에어비앤비",
                Map.of(IcalAdapter.ICAL_URL, feed.url()));
        // iCal 은 내보내기 URL 하나가 리스팅 하나다. 매핑도 하나다(조사-02 1절).
        channels.addMapping(connection.getId(), f.orgId(), f.unitId(), "listing-1", null);
        return connection;
    }

    private static String calendar(String... events) {
        return "BEGIN:VCALENDAR\r\nPRODID:-//Airbnb Inc//Hosting Calendar 1.0//EN\r\nVERSION:2.0\r\n"
                + String.join("", events) + "END:VCALENDAR\r\n";
    }

    private static String vevent(String uid, String start, String end) {
        return "BEGIN:VEVENT\r\nDTSTAMP:20260905T024836Z\r\n"
                + "DTSTART;VALUE=DATE:" + start + "\r\nDTEND;VALUE=DATE:" + end + "\r\n"
                + "SUMMARY:Airbnb (Not available)\r\nUID:" + uid + "\r\nEND:VEVENT\r\n";
    }

    private List<LocalDate> 체크인과_체크아웃(String uid) {
        return jdbc.queryForObject(
                "SELECT check_in, check_out FROM reservation WHERE channel_booking_id = ?",
                (rs, row) -> List.of(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)),
                uid);
    }

    private String 상태(String uid) {
        return jdbc.queryForObject(
                "SELECT status FROM reservation WHERE channel_booking_id = ?", String.class, uid);
    }

    private int 예약_건수(String uid) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE channel_booking_id = ?", Integer.class, uid);
    }

    private Integer 직전_일정수(ChannelConnection connection) {
        return jdbc.queryForObject(
                "SELECT last_event_count FROM channel_connection WHERE id = ?",
                Integer.class, connection.getId());
    }
}
