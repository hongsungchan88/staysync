package com.staysync.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.BookingService;
import com.staysync.booking.ChannelBookingCommand;
import com.staysync.booking.ChannelBookingIntake;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 작업지시 13 의 완료 조건 13·14. 리포트 지표 여섯.
 *
 * <p><b>분모가 0인 경우가 이 파일의 요점이다.</b> 예약이 하나도 없는 기간을 보는 것은
 * 흔한 일이고 — 새 숙소를 등록한 직후가 그렇다 — 그때 화면이 터지면 안 된다.
 * 산식마다 분모가 다르므로 여섯을 한 번에 확인한다.
 *
 * <p>지표는 숫자를 맞히는 것이 아니라 <b>정의가 맞는지</b>를 본다. 점유율의 분모가
 * "팔 수 있었던 방"인지, 취소율이 예약일 기준인지 같은 것이다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class ReportTest {

    private static final LocalDate 첫날 = LocalDate.of(2027, 8, 1);
    private static final LocalDate 끝날 = LocalDate.of(2027, 8, 31);

    @Autowired
    private ReportService reports;

    @Autowired
    private BookingService booking;

    @Autowired
    private ChannelBookingIntake intake;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    // --- 완료 조건 13 --------------------------------------------------------

    @Test
    @DisplayName("예약이 하나도 없어도 지표 여섯이 0으로 나온다")
    void 분모가_0이어도_터지지_않는다() {
        Fixture f = given("리포트빈값", (short) 1);

        ReportMetrics m = reports.of(f.orgId(), f.propertyId(), 첫날, 끝날);

        assertThat(m.soldNights()).isZero();
        assertThat(m.roomRevenue()).isEqualByComparingTo("0");
        assertThat(m.occupancyRate()).isEqualByComparingTo("0");
        assertThat(m.adr())
                .as("판매된 박이 0이면 ADR 의 분모가 0이다")
                .isEqualByComparingTo("0");
        assertThat(m.revPar()).isEqualByComparingTo("0");
        assertThat(m.leadTimeDays()).isEqualByComparingTo("0.0");
        assertThat(m.cancellationRate())
                .as("예약이 0건이면 취소율의 분모가 0이다")
                .isEqualByComparingTo("0");
        assertThat(m.channelMix()).isEmpty();

        // 판매 가능 객실박은 예약과 무관하다. 1실 × 31일이다.
        assertThat(m.availableNights()).isEqualTo(31);
    }

    @Test
    @DisplayName("점유율과 ADR 과 RevPAR 이 정의대로 나온다")
    void 세_지표가_정의대로_나온다() {
        Fixture f = given("리포트기본", (short) 2);
        // 2박 20만원. 2실 × 31일 = 62 객실박이 분모다.
        예약(f, 첫날.plusDays(4), 첫날.plusDays(6), 200_000);

        ReportMetrics m = reports.of(f.orgId(), f.propertyId(), 첫날, 끝날);

        assertThat(m.soldNights()).isEqualTo(2);
        assertThat(m.availableNights()).isEqualTo(62);
        assertThat(m.roomRevenue()).isEqualByComparingTo("200000");
        assertThat(m.occupancyRate()).isEqualByComparingTo("0.0323");   // 2/62
        assertThat(m.adr())
                .as("객실 매출 ÷ 판매된 객실박")
                .isEqualByComparingTo("100000");
        assertThat(m.revPar())
                .as("객실 매출 ÷ 판매 가능 객실박. ADR 과 분모가 다르다")
                .isEqualByComparingTo("3225.8065");
    }

    @Test
    @DisplayName("RevPAR 이 ADR 곱하기 점유율과 같다")
    void 세_지표가_항등식으로_맞물린다() {
        Fixture f = given("리포트항등", (short) 1);
        // 1실 × 20일 = 20 객실박이고 5박 50만원이다. 셋이 모두 나누어떨어지므로
        // 반올림 오차 없이 항등식 그대로 볼 수 있다.
        LocalDate 마지막날 = 첫날.plusDays(19);
        예약(f, 첫날.plusDays(4), 첫날.plusDays(9), 500_000);

        ReportMetrics m = reports.of(f.orgId(), f.propertyId(), 첫날, 마지막날);

        assertThat(m.availableNights()).isEqualTo(20);
        assertThat(m.soldNights()).isEqualTo(5);

        // **점유율과 RevPAR 의 분모가 갈라지면 여기서 실패한다.** 화면이 셋을 나란히
        // 띄우므로 누구든 곱해 보고, 안 맞으면 셋 다 의심받는다. 지금은 둘 다
        // availableNights 라 성립한다 — 한쪽을 고치면 이 줄이 잡는다.
        assertThat(m.revPar())
                .as("RevPAR = ADR × 점유율")
                .isEqualByComparingTo(m.adr().multiply(m.occupancyRate()));
    }

    @Test
    @DisplayName("판매중지한 날도 판매 가능 객실박에 들어간다")
    void 판매중지는_분모를_줄이지_않는다() {
        Fixture f = given("리포트중지", (short) 1);
        ReportMetrics 전 = reports.of(f.orgId(), f.propertyId(), 첫날, 끝날);

        jdbc.update("""
                INSERT INTO inventory_ledger (unit_id, stay_date, total_units, booked_units,
                                              held_units, stop_sell)
                VALUES (?, ?, 1, 0, 0, true)
                """, f.unitId(), 첫날.plusDays(10));

        ReportMetrics 후 = reports.of(f.orgId(), f.propertyId(), 첫날, 끝날);

        // 빼면 판매중지를 늘릴수록 점유율이 올라가는 지표가 된다.
        assertThat(후.availableNights()).isEqualTo(전.availableNights());
    }

    @Test
    @DisplayName("기간에 걸친 예약은 그 기간에 든 박만 세어진다")
    void 걸친_예약은_잘려서_세어진다() {
        Fixture f = given("리포트걸침", (short) 1);
        // 7/30 ~ 8/2. 8월 리포트에는 8/1 과 8/2 두 박만 들어간다.
        예약(f, 첫날.minusDays(2), 첫날.plusDays(2), 400_000);

        ReportMetrics m = reports.of(f.orgId(), f.propertyId(), 첫날, 끝날);

        assertThat(m.soldNights())
                .as("박 행이 하루씩 쪼개져 있어 자르는 코드가 따로 없다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("HOLD 는 팔린 것으로 세지 않는다")
    void 홀드는_매출이_아니다() {
        Fixture f = given("리포트홀드", (short) 1);
        booking.hold(f.propertyId(), f.unitId(),
                new StayPeriod(첫날.plusDays(4), 첫날.plusDays(6)),
                BigDecimal.valueOf(200_000), null);

        ReportMetrics m = reports.of(f.orgId(), f.propertyId(), 첫날, 끝날);

        // 15분 뒤 사라질 수 있는 점유다. 매출로 세면 리포트가 실제보다 커진다.
        assertThat(m.soldNights()).isZero();
        assertThat(m.roomRevenue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("취소율은 체크인 날짜 기준이고 만료된 홀드를 세지 않는다")
    void 취소율이_정의대로_나온다() {
        Fixture f = given("리포트취소", (short) 3);
        Reservation 살아있는것 = 예약(f, 첫날.plusDays(4), 첫날.plusDays(6), 200_000);
        Reservation 취소할것 = 예약(f, 첫날.plusDays(10), 첫날.plusDays(12), 200_000);
        booking.cancel(취소할것.getId());

        // 만료된 홀드. 손님이 취소한 것이 아니므로 분모에도 분자에도 들어가지 않는다.
        Reservation 만료될것 = booking.hold(f.propertyId(), f.unitId(),
                new StayPeriod(첫날.plusDays(20), 첫날.plusDays(22)),
                BigDecimal.valueOf(200_000), null);
        booking.expireHold(만료될것.getId());

        ReportMetrics m = reports.of(f.orgId(), f.propertyId(), 첫날, 끝날);

        // 2건 중 1건 취소다. 만료된 홀드를 세면 3분의 1이 된다.
        //
        // 기간의 뜻은 **숙박**이다. 예약일 기준으로 세면 다음 달 리포트의 취소율이
        // 언제나 0이 되고, 같은 화면의 점유율과 뜻이 어긋난다.
        assertThat(m.cancellationRate()).isEqualByComparingTo("0.5000");
        assertThat(살아있는것.getId()).isNotNull();
    }

    @Test
    @DisplayName("채널 믹스가 건수와 매출 비중으로 나온다")
    void 채널_믹스가_나온다() {
        Fixture f = given("리포트채널", (short) 2);
        예약(f, 첫날.plusDays(4), 첫날.plusDays(6), 200_000);        // DIRECT
        채널예약(f, 첫날.plusDays(10), 첫날.plusDays(11), 100_000);   // MOCK_REPORT

        ReportMetrics m = reports.of(f.orgId(), f.propertyId(), 첫날, 끝날);

        assertThat(m.channelMix()).hasSize(2);
        // 매출 내림차순이다. 화면이 큰 것부터 보여 준다.
        ReportMetrics.ChannelShare 첫째 = m.channelMix().get(0);
        assertThat(첫째.channelCode()).isEqualTo("DIRECT");
        assertThat(첫째.reservations()).isEqualTo(1);
        assertThat(첫째.revenue()).isEqualByComparingTo("200000");
        assertThat(첫째.revenueShare())
                .as("30만원 중 20만원")
                .isEqualByComparingTo("0.6667");
    }

    @Test
    @DisplayName("채널로 수신된 예약이 판매 객실박과 채널 믹스에 반영된다")
    void 채널_수신_예약이_리포트에_잡힌다() {
        // 확인-08 3절 1번. 채널 수신은 박 행을 쓰지 않아 캘린더에는 있고 리포트에는
        // 없었다. 13·14 가 채널 예약을 UPDATE 로 흉내 내서 못 잡았던 자리다.
        Fixture f = given("리포트채널수신", (short) 1);
        채널예약(f, 첫날.plusDays(10), 첫날.plusDays(12), 300_000);

        ReportMetrics m = reports.of(f.orgId(), f.propertyId(), 첫날, 끝날);

        assertThat(m.soldNights()).isEqualTo(2);
        assertThat(m.channelMix()).singleElement().satisfies(share -> {
            assertThat(share.channelCode()).isEqualTo("MOCK_REPORT");
            assertThat(share.reservations()).isEqualTo(1);
            assertThat(share.revenue()).isEqualByComparingTo("300000");
        });

        // 채널이 날짜를 바꾸면 박 행도 따라가야 한다. 옛 박이 남으면 두 배로 센다.
        intake.ingest(new ChannelBookingCommand(f.propertyId(), f.unitId(), "MOCK_REPORT",
                "BK-" + f.unitId(), null, 첫날.plusDays(20), 첫날.plusDays(21),
                BigDecimal.valueOf(150_000), 2, false));
        assertThat(reports.of(f.orgId(), f.propertyId(), 첫날, 끝날).soldNights()).isEqualTo(1);
    }

    // --- 완료 조건 14 --------------------------------------------------------

    @Test
    @DisplayName("기간 필터가 동작한다")
    void 기간으로_거른다() {
        Fixture f = given("리포트기간", (short) 1);
        예약(f, 첫날.plusDays(4), 첫날.plusDays(6), 200_000);

        assertThat(reports.of(f.orgId(), f.propertyId(), 첫날, 끝날).soldNights()).isEqualTo(2);
        assertThat(reports.of(f.orgId(), f.propertyId(),
                첫날.plusMonths(2), 끝날.plusMonths(2)).soldNights())
                .as("다른 달을 물으면 그 달의 박만 세어야 한다")
                .isZero();
    }

    @Test
    @DisplayName("숙소 필터가 동작하고 남의 숙소는 빈 결과다")
    void 숙소로_거른다() {
        Fixture 내것 = given("리포트내것", (short) 1);
        Fixture 남의것 = given("리포트남의것", (short) 1);
        예약(남의것, 첫날.plusDays(4), 첫날.plusDays(6), 200_000);

        // 내 조직으로 남의 숙소를 물으면 빈 결과다. 있는지 없는지 알려 주지 않는다.
        assertThat(reports.of(내것.orgId(), 남의것.propertyId(), 첫날, 끝날).soldNights())
                .isZero();
        // 숙소를 지정하지 않으면 내 조직의 숙소 전부다.
        assertThat(reports.of(내것.orgId(), null, 첫날, 끝날).soldNights()).isZero();
        assertThat(reports.of(남의것.orgId(), null, 첫날, 끝날).soldNights()).isEqualTo(2);
    }

    // --- 픽스처 ---------------------------------------------------------------

    private record Fixture(Long orgId, Long propertyId, Long unitId) {
    }

    private Reservation 예약(Fixture f, LocalDate 체크인, LocalDate 체크아웃, int 금액) {
        return booking.registerManual(f.propertyId(), f.unitId(),
                new StayPeriod(체크인, 체크아웃), BigDecimal.valueOf(금액),
                (short) 2, (short) 0, null);
    }

    /**
     * 채널이 만든 예약. <b>실제 수신 경로를 탄다.</b> 예전에는 수기 예약의
     * {@code channel_code} 를 UPDATE 로 바꿔 흉내 냈고, 그래서 채널 수신이 박 행을
     * 안 쓰는 것을 13·14 가 못 잡았다. 픽스처는 서버가 실제로 하는 일과 같아야 한다.
     */
    private void 채널예약(Fixture f, LocalDate 체크인, LocalDate 체크아웃, int 금액) {
        // 채널 예약번호는 (channel_code, channel_booking_id) 로 전역 유일이라 픽스처마다 갈라야 한다.
        intake.ingest(new ChannelBookingCommand(f.propertyId(), f.unitId(), "MOCK_REPORT",
                "BK-" + f.unitId(), null, 체크인, 체크아웃, BigDecimal.valueOf(금액), 1, false));
    }

    private Fixture given(String name, short totalUnits) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES (?) RETURNING id",
                Long.class, name + " 조직");
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        Long unitId = unitRegistration.register(
                propertyId, name + " 객실", UnitKind.ENTIRE_PLACE, totalUnits,
                BigDecimal.valueOf(100_000));
        return new Fixture(orgId, propertyId, unitId);
    }
}
