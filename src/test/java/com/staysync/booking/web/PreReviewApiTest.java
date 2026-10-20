package com.staysync.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.staysync.booking.ChannelBookingCommand;
import com.staysync.booking.ChannelBookingIntake;
import com.staysync.support.ApiTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 작업지시-26 — 심사 전 결함 다섯(검토-02 3절 0~4). 전부 HTTP 로 부르고, 거절됐으면 아무것도 안 바뀌었는지 DB 로 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class PreReviewApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChannelBookingIntake intake;

    // --- A. 충돌 해소는 그 충돌의 예약만 -------------------------------------------------

    @Test
    @DisplayName("A — 다른 조직의 예약을 취소·업그레이드로 보내면 400 이고 아무것도 바뀌지 않는다")
    void 충돌_해소는_다른_조직의_예약을_움직이지_않는다() throws Exception {
        Fixture 나 = 숙소와_판매단위(가입(새이메일()), 1, 2);
        Long 충돌 = 충돌을_만든다(나, LocalDate.of(2027, 7, 1));
        Fixture 남 = 숙소와_판매단위(가입(새이메일()), 1, 1);
        Long 남의예약 = 예약등록(남, "2027-07-10", "2027-07-12");

        Snapshot before = snapshot(나, 남, 남의예약, 충돌);
        해소(나, 충돌, "CANCELLED", 남의예약, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이 충돌에 걸린 예약이 아닙니다."));
        해소(나, 충돌, "UPGRADED", 남의예약, 나.secondUnitId()).andExpect(status().isBadRequest());

        assertThat(snapshot(나, 남, 남의예약, 충돌)).isEqualTo(before);
    }

    @Test
    @DisplayName("A — 같은 조직이어도 그 충돌에 없는 예약이면 400 이고 아무것도 바뀌지 않는다")
    void 충돌_해소는_충돌에_없는_예약을_움직이지_않는다() throws Exception {
        Fixture 나 = 숙소와_판매단위(가입(새이메일()), 1, 2);
        Long 충돌 = 충돌을_만든다(나, LocalDate.of(2027, 7, 1));
        Long 다른예약 = 예약등록(나, "2027-07-20", "2027-07-22");

        Snapshot before = snapshot(나, 나, 다른예약, 충돌);
        해소(나, 충돌, "CANCELLED", 다른예약, null).andExpect(status().isBadRequest());
        해소(나, 충돌, "UPGRADED", 다른예약, 나.secondUnitId()).andExpect(status().isBadRequest());

        assertThat(snapshot(나, 나, 다른예약, 충돌)).isEqualTo(before);
    }

    // --- B. 위젯은 지난 날짜를 팔지 않는다 -------------------------------------------------

    @Test
    @DisplayName("B — 어제 체크인 HOLD 는 400, 오늘 체크인 HOLD 는 된다")
    void 위젯은_지난_날짜를_팔지_않는다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()), 1, 1);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

        홀드(f, today.minusDays(1), today.plusDays(1), 200000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("지난 날짜는 예약할 수 없습니다."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservation WHERE unit_id = ?",
                Long.class, f.unitId())).isZero();

        홀드(f, today, today.plusDays(1), 100000).andExpect(status().isCreated());
    }

    // --- C. 채널 예약은 날짜를 옮기지 않는다 -----------------------------------------------

    @Test
    @DisplayName("C — iCal 예약의 날짜 변경은 409·변화 없음, 인원 변경은 된다, 직접예약 날짜 변경은 된다")
    void 채널_예약은_날짜를_옮기지_않는다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()), 1, 1);
        Long ical = 채널예약(f, LocalDate.of(2027, 8, 1), LocalDate.of(2027, 8, 3));
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT check_in, check_out, status FROM reservation WHERE id = ?", ical);
        String ledgerBefore = 원장(f);

        변경(f, ical, "{\"checkIn\":\"2027-08-05\",\"checkOut\":\"2027-08-07\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHANNEL_RESERVATION_LOCKED"));
        assertThat(jdbc.queryForMap("SELECT check_in, check_out, status FROM reservation WHERE id = ?", ical))
                .isEqualTo(before);
        assertThat(원장(f)).isEqualTo(ledgerBefore);

        변경(f, ical, "{\"adults\":3}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT adults FROM reservation WHERE id = ?", Integer.class, ical))
                .isEqualTo(3);

        Long direct = 예약등록(f, "2027-08-10", "2027-08-12");
        변경(f, direct, "{\"checkIn\":\"2027-08-14\",\"checkOut\":\"2027-08-16\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkIn").value("2027-08-14"));
    }

    // --- D. 직접예약 취소 ---------------------------------------------------------------

    @Test
    @DisplayName("D — 직접예약 취소는 CANCELLED·원장 반납, 채널 예약 취소는 409·변화 없음")
    void 직접예약만_취소한다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()), 1, 1);
        Long direct = 예약등록(f, "2027-09-01", "2027-09-03");
        assertThat(booked(f, "2027-09-01")).isEqualTo(1);

        취소(f, direct).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(booked(f, "2027-09-01")).as("원장 반납").isZero();
        assertThat(booked(f, "2027-09-02")).isZero();

        Long ical = 채널예약(f, LocalDate.of(2027, 9, 10), LocalDate.of(2027, 9, 12));
        취소(f, ical).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("채널에서 들어온 예약은 그 채널에서 취소해야 합니다."));
        assertThat(jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, ical))
                .isEqualTo("CONFIRMED");
        assertThat(booked(f, "2027-09-10")).isEqualTo(1);
    }

    // --- E. 일괄 편집의 잘못된 값 -------------------------------------------------------

    @Test
    @DisplayName("E — 음수 고정가·-100% 비율은 400 과 읽히는 문구, ERROR 로그 없음")
    void 일괄_편집의_잘못된_값은_400이다(CapturedOutput output) throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()), 1, 1);

        일괄편집(f, "\"priceMode\":\"FIXED\",\"price\":-1000")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BULK_EDIT"))
                .andExpect(jsonPath("$.message").value("요금은 0 이상이어야 합니다."));
        일괄편집(f, "\"priceMode\":\"PERCENT\",\"priceRate\":-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("비율은 -100% 보다 커야 합니다."));

        assertThat(output.getAll()).doesNotContain("처리하지 못한 예외");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_calendar WHERE rate_plan_id = ?",
                Long.class, f.ratePlanId())).isZero();
    }

    // --- 픽스처 ---------------------------------------------------------------------

    /** 판매 단위 둘째는 업그레이드 대상이다(없으면 null). */
    private record Fixture(Session session, Long propertyId, Long unitId, Long secondUnitId, Long ratePlanId) {
    }

    /** 충돌 하나와 그 숫자들. 거절 뒤 이 값이 그대로여야 한다. */
    private record Snapshot(Map<String, Object> 충돌, Map<String, Object> 대상예약,
                            String 내원장, String 남원장, long 감사) {
    }

    private Snapshot snapshot(Fixture 나, Fixture 대상쪽, Long 대상예약, Long 충돌) {
        return new Snapshot(
                jdbc.queryForMap("SELECT status, resolution FROM overbooking_conflict WHERE id = ?", 충돌),
                jdbc.queryForMap("SELECT status, unit_id, property_id FROM reservation WHERE id = ?", 대상예약),
                원장(나), 원장(대상쪽),
                jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class));
    }

    private String 원장(Fixture f) {
        return jdbc.queryForList("""
                SELECT unit_id, stay_date, booked_units, held_units, overbooked_units FROM inventory_ledger
                WHERE unit_id IN (SELECT id FROM unit WHERE property_id = ?) ORDER BY unit_id, stay_date
                """, f.propertyId()).toString();
    }

    private int booked(Fixture f, String day) {
        return jdbc.queryForObject("SELECT booked_units FROM inventory_ledger WHERE unit_id = ? AND stay_date = ?",
                Integer.class, f.unitId(), LocalDate.parse(day));
    }

    /** 1실 판매 단위에 직접예약과 같은 날 채널 예약 → 초과 받아들임 → OPEN 충돌 하나. */
    private Long 충돌을_만든다(Fixture f, LocalDate day) throws Exception {
        예약등록(f, day.toString(), day.plusDays(1).toString());
        채널예약(f, day, day.plusDays(1));
        return jdbc.queryForObject(
                "SELECT id FROM overbooking_conflict WHERE unit_id = ? AND stay_date = ? AND status = 'OPEN'",
                Long.class, f.unitId(), day);
    }

    /** 에어비앤비 iCal 이 넣은 예약. 채널 수신의 진짜 입구로 넣는다. */
    private Long 채널예약(Fixture f, LocalDate checkIn, LocalDate checkOut) {
        String uid = "uid-" + UUID.randomUUID();
        intake.ingest(new ChannelBookingCommand(f.propertyId(), f.unitId(), "AIRBNB_ICAL", uid, null,
                checkIn, checkOut, null, 0, 0, null, false, Set.of()));
        return jdbc.queryForObject("SELECT id FROM reservation WHERE channel_booking_id = ?", Long.class, uid);
    }

    private org.springframework.test.web.servlet.ResultActions 해소(
            Fixture f, Long conflictId, String resolution, Long reservationId, Long targetUnitId) throws Exception {
        return mvc.perform(post("/api/conflicts/" + conflictId + "/resolve")
                .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"resolution":"%s","reservationId":%d,"targetUnitId":%s}
                        """.formatted(resolution, reservationId, targetUnitId)));
    }

    private org.springframework.test.web.servlet.ResultActions 홀드(
            Fixture f, LocalDate checkIn, LocalDate checkOut, int quoted) throws Exception {
        return mvc.perform(post("/public/booking/" + f.propertyId() + "/hold")
                .header("X-Forwarded-For", 새IP())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"unitId":%d,"checkIn":"%s","checkOut":"%s",
                         "quotedAmount":%d,"adults":2,"children":0,"guestName":"위젯 손님"}
                        """.formatted(f.unitId(), checkIn, checkOut, quoted)));
    }

    private org.springframework.test.web.servlet.ResultActions 변경(Fixture f, Long id, String body)
            throws Exception {
        return mvc.perform(patch("/api/reservations/" + id)
                .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions 취소(Fixture f, Long id) throws Exception {
        return mvc.perform(post("/api/reservations/" + id + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, f.session().bearer()));
    }

    private org.springframework.test.web.servlet.ResultActions 일괄편집(Fixture f, String price) throws Exception {
        return mvc.perform(post("/api/properties/" + f.propertyId() + "/calendar/bulk-edit")
                .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"unitIds":[%d],"from":"2027-10-01","to":"2027-10-03",%s,"dryRun":false}
                        """.formatted(f.unitId(), price)));
    }

    private Fixture 숙소와_판매단위(Session session, int totalUnits, int unitCount) throws Exception {
        MvcResult property = mvc.perform(post("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"심사 전 숙소\",\"address\":\"서울\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Long propertyId = json.readTree(property.getResponse().getContentAsString()).get("id").asLong();

        Long first = null;
        Long second = null;
        for (int i = 0; i < unitCount; i++) {
            MvcResult unit = mvc.perform(post("/api/properties/" + propertyId + "/units")
                            .header(HttpHeaders.AUTHORIZATION, session.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"객실 %d","unitKind":"ENTIRE_PLACE","totalUnits":%d,"basePrice":100000}
                                    """.formatted(i + 1, totalUnits)))
                    .andExpect(status().isCreated())
                    .andReturn();
            Long id = json.readTree(unit.getResponse().getContentAsString()).get("id").asLong();
            if (i == 0) {
                first = id;
            } else {
                second = id;
            }
        }
        Long ratePlanId = jdbc.queryForObject(
                "SELECT id FROM rate_plan WHERE unit_id = ? AND is_default", Long.class, first);
        return new Fixture(session, propertyId, first, second, ratePlanId);
    }

    private Long 예약등록(Fixture f, String checkIn, String checkOut) throws Exception {
        MvcResult result = mvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyId":%d,"unitId":%d,"checkIn":"%s","checkOut":"%s",
                                 "totalAmount":%s,"adults":2,"children":0,"guestName":"홍길동"}
                                """.formatted(f.propertyId(), f.unitId(), checkIn, checkOut,
                                BigDecimal.valueOf(200000))))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private static String 새이메일() {
        return "pre-review-" + UUID.randomUUID() + "@example.com";
    }
}
