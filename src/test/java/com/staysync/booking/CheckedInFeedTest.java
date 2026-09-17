package com.staysync.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.domain.Reservation;
import com.staysync.channel.ChannelBookingPoller;
import com.staysync.channel.adapter.ical.IcalAdapter;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.IcalStubServer;
import com.staysync.channel.support.SyncTestBase;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 작업지시-18 A. 투숙 중(CHECKED_IN) 예약과 iCal 폴링. 완료 조건 1·2·3.
 *
 * <p>스텁 발행자 → {@code IcalAdapter} → 폴러 → 수신부의 실제 경로다. 체크인은 실제
 * 서비스 API({@code BookingService.checkIn})로 만든다 — UPDATE 로 흉내 내지 않는다.
 *
 * <p>날짜는 <b>오늘 기준</b>이다. {@code cancelMissing} 의 "체크아웃이 지난 예약은 건드리지
 * 않는다" 가드가 오늘을 보므로, 고정 날짜를 쓰면 언젠가 그 가드에 걸려 이 테스트가
 * 검증하려는 자리를 지나지 않게 된다.
 */
class CheckedInFeedTest extends SyncTestBase {

    private static final DateTimeFormatter ICAL = DateTimeFormatter.BASIC_ISO_DATE;

    @Autowired
    private ChannelBookingPoller poller;

    @Autowired
    private BookingService booking;

    @Autowired
    private ReservationRepository reservations;

    private IcalStubServer feed;

    @BeforeEach
    void 발행자를_띄운다() {
        feed = new IcalStubServer();
    }

    @AfterEach
    void 발행자를_닫는다() {
        feed.close();
    }

    // --- 완료 조건 1·2 ---------------------------------------------------------

    @Test
    @DisplayName("투숙 중인 예약이 발행물에서 빠져도 취소되지 않고, 확정 예약은 지금처럼 취소된다")
    void 투숙_중이면_취소하지_않는다() {
        Fixture f = given("투숙중-취소", (short) 2);
        ChannelConnection c = icalConnection(f);
        LocalDate 어제 = LocalDate.now().minusDays(1);
        feed.publish(calendar(
                vevent("in-1", 어제, 어제.plusDays(3)),          // 투숙 중이 될 예약
                vevent("cf-1", 어제.plusDays(10), 어제.plusDays(12))), null);
        assertThat(poller.pollOne(c)).isEqualTo(2);
        booking.checkIn(id("in-1"));
        assertThat(상태("in-1")).isEqualTo("CHECKED_IN");

        // 둘 다 발행물에서 사라졌다.
        feed.publish(calendar(), null);
        poller.pollOne(c);

        assertThat(상태("in-1"))
                .as("손님은 실제로 숙소에 있다. 추정으로 취소하면 그 방이 다시 팔린다")
                .isEqualTo("CHECKED_IN");
        assertThat(상태("cf-1"))
                .as("확정 예약이 빠지는 것은 진짜 취소다. 기존 동작 그대로")
                .isEqualTo("CANCELLED");
        assertThat(booked(f.unitId(), 어제.plusDays(1)))
                .as("투숙 중 예약의 재고는 그대로 잡혀 있다")
                .isEqualTo(1);
    }

    // --- 완료 조건 3 ------------------------------------------------------------

    @Test
    @DisplayName("투숙 중인 예약의 날짜가 발행물에서 바뀌면 재고 원장이 따라간다")
    void 투숙_중_날짜_변경이_원장을_옮긴다() {
        Fixture f = given("투숙중-연장");
        ChannelConnection c = icalConnection(f);
        LocalDate 어제 = LocalDate.now().minusDays(1);
        LocalDate 원래체크아웃 = 어제.plusDays(3);
        feed.publish(calendar(vevent("in-2", 어제, 원래체크아웃)), null);
        poller.pollOne(c);
        booking.checkIn(id("in-2"));

        // 게스트가 하루 연장했다. 발행물의 DTEND 가 하루 뒤로 간다.
        LocalDate 새체크아웃 = 원래체크아웃.plusDays(1);
        feed.publish(calendar(vevent("in-2", 어제, 새체크아웃)), null);
        poller.pollOne(c);

        Reservation r = reservations.findById(id("in-2")).orElseThrow();
        assertThat(r.getPeriod().checkOut()).isEqualTo(새체크아웃);
        assertThat(r.getStatus().name()).isEqualTo("CHECKED_IN");
        // 예전에는 isActive() 가 CHECKED_IN 을 빼서 기간만 바뀌고 원장은 옛 날짜에 남았다.
        // 연장된 밤이 잡혀 있어야 그날 다른 채널에 팔리지 않는다.
        assertThat(booked(f.unitId(), 원래체크아웃))
                .as("연장된 밤(옛 체크아웃 날)이 잡혀야 한다")
                .isEqualTo(1);
        assertThat(booked(f.unitId(), 새체크아웃))
                .as("새 체크아웃 날은 묵지 않는다")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("투숙 중인 예약을 취소하면 재고가 반납된다")
    void 투숙_중_취소는_재고를_반납한다() {
        // 8절 표의 확인 항목. cancel 은 isActive() 를 안 쓰고 상태별 switch 라 원래부터 반납했다.
        Fixture f = given("투숙중-반납");
        ChannelConnection c = icalConnection(f);
        LocalDate 어제 = LocalDate.now().minusDays(1);
        feed.publish(calendar(vevent("in-3", 어제, 어제.plusDays(2))), null);
        poller.pollOne(c);
        booking.checkIn(id("in-3"));
        assertThat(booked(f.unitId(), 어제)).isEqualTo(1);

        booking.cancel(id("in-3"));

        assertThat(상태("in-3")).isEqualTo("CANCELLED");
        assertThat(booked(f.unitId(), 어제)).isEqualTo(0);
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

    private static String vevent(String uid, LocalDate start, LocalDate endExclusive) {
        return "BEGIN:VEVENT\r\nDTSTAMP:20260905T024836Z\r\n"
                + "DTSTART;VALUE=DATE:" + ICAL.format(start) + "\r\nDTEND;VALUE=DATE:" + ICAL.format(endExclusive) + "\r\n"
                + "SUMMARY:Reserved\r\nUID:" + uid + "\r\nEND:VEVENT\r\n";
    }

    private Long id(String uid) {
        return jdbc.queryForObject(
                "SELECT id FROM reservation WHERE channel_booking_id = ?", Long.class, uid);
    }

    private String 상태(String uid) {
        return jdbc.queryForObject(
                "SELECT status FROM reservation WHERE channel_booking_id = ?", String.class, uid);
    }

    private int booked(Long unitId, LocalDate date) {
        Integer v = jdbc.queryForObject(
                "SELECT coalesce(sum(booked_units), 0) FROM inventory_ledger WHERE unit_id = ? AND stay_date = ?",
                Integer.class, unitId, date);
        return v == null ? 0 : v;
    }
}
