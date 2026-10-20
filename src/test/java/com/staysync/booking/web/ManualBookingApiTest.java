package com.staysync.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.staysync.support.ApiTestBase;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 작업지시-28 B — 캘린더에서 입력하는 예약. 전부 HTTP 로 만들고 원장·감사·이벤트를 DB 로 본다.
 */
class ManualBookingApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("금액을 비우면 요금표 합계 — DIRECT·CONFIRMED, 원장 확보, 감사 1, 확정 이벤트 1")
    void 금액을_비우면_요금표_합계로_만든다() throws Exception {
        Fixture f = 숙소와_판매단위();

        MvcResult result = 입력(f, "2027-04-01", "2027-04-03", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channelCode").value("DIRECT"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(200000))
                .andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        assertThat(booked(f, "2027-04-01")).isEqualTo(1);
        assertThat(booked(f, "2027-04-02")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_type = 'RESERVATION' AND entity_id = ? AND action = 'MANUAL_CREATE'",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'RESERVATION_CONFIRMED'",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT sum(price) FROM reservation_night WHERE reservation_id = ?", Integer.class, id))
                .isEqualTo(200000);
        // 게스트 연락처는 기존 암호화 경로 — 평문 칸이 없다.
        assertThat(jdbc.queryForObject(
                "SELECT phone_enc IS NOT NULL AND phone_hash IS NOT NULL FROM guest g JOIN reservation r ON r.guest_id = g.id WHERE r.id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("사람이 적은 금액이 있으면 그 값이다")
    void 적은_금액이_있으면_그_값이다() throws Exception {
        Fixture f = 숙소와_판매단위();

        입력(f, "2027-04-10", "2027-04-12", "150000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(150000));
    }

    @Test
    @DisplayName("재고가 없는 날은 409 와 서버 문구, 원장·예약 그대로")
    void 재고가_없으면_거절한다() throws Exception {
        Fixture f = 숙소와_판매단위();
        입력(f, "2027-05-01", "2027-05-03", null).andExpect(status().isCreated());
        String 원장전 = 원장(f);
        int 예약전 = 예약수(f);

        입력(f, "2027-05-02", "2027-05-04", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("판매 가능한 재고가 없습니다")));

        assertThat(원장(f)).isEqualTo(원장전);
        assertThat(예약수(f)).isEqualTo(예약전);
    }

    @Test
    @DisplayName("최소 숙박에 걸리면 409 MIN_STAY_NOT_MET, 원장·예약 그대로")
    void 최소_숙박에_걸리면_거절한다() throws Exception {
        Fixture f = 숙소와_판매단위();
        mvc.perform(post("/api/properties/" + f.propertyId() + "/calendar/bulk-edit")
                        .header(HttpHeaders.AUTHORIZATION, f.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitIds":[%d],"from":"2027-06-01","to":"2027-06-10","minStay":2,"dryRun":false}
                                """.formatted(f.unitId())))
                .andExpect(status().isOk());
        String 원장전 = 원장(f);

        입력(f, "2027-06-05", "2027-06-06", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MIN_STAY_NOT_MET"));

        assertThat(원장(f)).isEqualTo(원장전);
        assertThat(예약수(f)).isZero();
    }

    @Test
    @DisplayName("체크아웃이 체크인보다 앞이면 400 — 서버 오류가 아니다")
    void 날짜가_거꾸로면_400이다() throws Exception {
        Fixture f = 숙소와_판매단위();

        입력(f, "2027-07-03", "2027-07-01", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(예약수(f)).isZero();
    }

    // --- 픽스처 ---------------------------------------------------------------------

    private record Fixture(String bearer, Long propertyId, Long unitId) {
    }

    private ResultActions 입력(Fixture f, String checkIn, String checkOut, String amount) throws Exception {
        return mvc.perform(post("/api/reservations")
                .header(HttpHeaders.AUTHORIZATION, f.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"propertyId":%d,"unitId":%d,"checkIn":"%s","checkOut":"%s",
                         "totalAmount":%s,"adults":2,"children":1,
                         "guestName":"전화 손님","guestPhone":"01055556666"}
                        """.formatted(f.propertyId(), f.unitId(), checkIn, checkOut, amount)));
    }

    private int booked(Fixture f, String day) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(booked_units), 0) FROM inventory_ledger WHERE unit_id = ? AND stay_date = ?",
                Integer.class, f.unitId(), LocalDate.parse(day));
    }

    private String 원장(Fixture f) {
        return jdbc.queryForList(
                "SELECT stay_date, booked_units, held_units FROM inventory_ledger WHERE unit_id = ? ORDER BY stay_date",
                f.unitId()).toString();
    }

    private int 예약수(Fixture f) {
        return jdbc.queryForObject("SELECT count(*) FROM reservation WHERE unit_id = ?", Integer.class, f.unitId());
    }

    /** 숙소 하나·1실 판매 단위 하나, 기본 요금 10만원. */
    private Fixture 숙소와_판매단위() throws Exception {
        Session session = 가입("manual-" + UUID.randomUUID() + "@example.com");
        MvcResult property = mvc.perform(post("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"예약 입력 숙소\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Long propertyId = json.readTree(property.getResponse().getContentAsString()).get("id").asLong();
        MvcResult unit = mvc.perform(post("/api/properties/" + propertyId + "/units")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"본채","unitKind":"ENTIRE_PLACE","totalUnits":1,"basePrice":100000}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        Long unitId = json.readTree(unit.getResponse().getContentAsString()).get("id").asLong();
        return new Fixture(session.bearer(), propertyId, unitId);
    }
}
