package com.staysync.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.staysync.support.ApiTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** 완료 조건 1~7. 캘린더 조회 API. */
class CalendarApiTest extends ApiTestBase {

    private static final String 전화번호 = "010-5555-6666";
    private static final String 이메일 = "cal-guest@example.com";

    @Autowired
    private JdbcTemplate jdbc;

    // --- 완료 조건 1 ---------------------------------------------------------

    @Test
    @DisplayName("남의 조직 숙소의 캘린더를 요청하면 404 가 나온다")
    void 다른_조직의_캘린더는_조회되지_않는다() throws Exception {
        Fixture 갑 = 숙소와_판매단위(가입(새이메일()));
        Session 을 = 가입(새이메일());

        mvc.perform(캘린더요청(갑.propertyId(), "2027-03-01", "2027-03-30")
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPERTY_NOT_FOUND"));

        // 갑 본인은 볼 수 있다
        mvc.perform(캘린더요청(갑.propertyId(), "2027-03-01", "2027-03-30")
                        .header(HttpHeaders.AUTHORIZATION, 갑.session().bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void 토큰_없이_캘린더를_부르면_401이다() throws Exception {
        mvc.perform(캘린더요청(1L, "2027-03-01", "2027-03-30"))
                .andExpect(status().isUnauthorized());
    }

    // --- 완료 조건 2 ---------------------------------------------------------

    @Test
    @DisplayName("원장에 행이 없는 날의 avail 은 total_units 와 같다")
    void 원장이_비어_있으면_전체_수량이_남아_있다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()), 3);

        MvcResult 결과 = mvc.perform(캘린더요청(f.propertyId(), "2027-04-01", "2027-04-05")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isOk())
                .andReturn();

        var days = json.readTree(결과.getResponse().getContentAsString())
                .get("units").get(0).get("days");

        assertThat(days.size()).isEqualTo(5);
        // 아무도 예약하지 않은 날이다. 0 이면 빈 그리드가 전부 매진으로 보인다.
        days.forEach(day -> assertThat(day.get("avail").asInt())
                .as("%s 의 avail", day.get("date").asText())
                .isEqualTo(3));
    }

    @Test
    void 예약이_있는_날만_재고가_줄어든다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()), 2);
        예약등록(f, "2027-05-03", "2027-05-05");

        var days = 날짜별셀(f, "2027-05-01", "2027-05-06");

        assertThat(days.get("2027-05-02").get("avail").asInt()).isEqualTo(2);
        assertThat(days.get("2027-05-03").get("avail").asInt()).isEqualTo(1);
        assertThat(days.get("2027-05-04").get("avail").asInt()).isEqualTo(1);
        // 체크아웃일은 숙박일이 아니라 재고를 차지하지 않는다
        assertThat(days.get("2027-05-05").get("avail").asInt()).isEqualTo(2);
    }

    // --- 완료 조건 3 ---------------------------------------------------------

    @Test
    @DisplayName("rate_calendar 에 값이 없는 날의 price 는 base_price 로 떨어진다")
    void 요금이_없으면_기본_요금으로_떨어진다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));

        var days = 날짜별셀(f, "2027-06-01", "2027-06-03");

        // 판매 단위 등록 시 base_price 를 80000 으로 넣었다
        days.forEach((date, cell) -> {
            assertThat(cell.get("price").asInt()).as("%s 의 price", date).isEqualTo(80000);
            assertThat(cell.get("minStay").asInt()).as("%s 의 minStay", date).isEqualTo(1);
        });
    }

    @Test
    void 요금이_설정된_날은_그_값을_쓴다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        Long ratePlanId = 기본요금제(f.unitId());
        요금설정(ratePlanId, "2027-07-02", 150000, 2);

        var days = 날짜별셀(f, "2027-07-01", "2027-07-03");

        assertThat(days.get("2027-07-01").get("price").asInt()).isEqualTo(80000);
        assertThat(days.get("2027-07-02").get("price").asInt()).isEqualTo(150000);
        assertThat(days.get("2027-07-02").get("minStay").asInt()).isEqualTo(2);
        assertThat(days.get("2027-07-03").get("price").asInt()).isEqualTo(80000);
    }

    // --- 완료 조건 4 ---------------------------------------------------------

    @Test
    @DisplayName("기간이 365일을 넘으면 거절한다")
    void 기간_상한을_넘으면_거절한다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));

        // 2027-01-01 ~ 2028-01-01 은 양끝 포함 367일이다
        mvc.perform(캘린더요청(f.propertyId(), "2027-01-01", "2028-01-01")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CALENDAR_RANGE"));

        // 경계는 통과해야 한다. 2027-01-01 ~ 2027-12-31 은 365일이다.
        mvc.perform(캘린더요청(f.propertyId(), "2027-01-01", "2027-12-31")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void 시작일이_종료일보다_뒤면_거절한다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));

        mvc.perform(캘린더요청(f.propertyId(), "2027-08-10", "2027-08-01")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CALENDAR_RANGE"));
    }

    // --- 완료 조건 5 ---------------------------------------------------------

    @Test
    @DisplayName("기간을 가로지르는 예약도 응답에 포함된다")
    void 조회_기간을_넘어서는_예약도_포함된다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        // 체크인이 from 이전, 체크아웃이 to 이후다. 경계에서 빠지기 쉽다.
        Long reservationId = 예약등록(f, "2027-09-01", "2027-09-30");

        MvcResult 결과 = mvc.perform(캘린더요청(f.propertyId(), "2027-09-10", "2027-09-20")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isOk())
                .andReturn();

        var reservations = json.readTree(결과.getResponse().getContentAsString()).get("reservations");
        assertThat(reservations.size()).isEqualTo(1);
        assertThat(reservations.get(0).get("id").asLong()).isEqualTo(reservationId);
        assertThat(reservations.get(0).get("checkIn").asText()).isEqualTo("2027-09-01");
        assertThat(reservations.get(0).get("checkOut").asText()).isEqualTo("2027-09-30");
    }

    @Test
    void 경계에_걸친_예약이_빠지지_않는다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()), 5);
        // 조회 기간 2027-10-10 ~ 2027-10-20 기준
        예약등록(f, "2027-10-05", "2027-10-11");   // 앞쪽 경계에 걸침
        예약등록(f, "2027-10-19", "2027-10-25");   // 뒤쪽 경계에 걸침
        예약등록(f, "2027-10-20", "2027-10-22");   // 마지막 날 체크인
        예약등록(f, "2027-10-01", "2027-10-05");   // 완전히 이전 — 빠져야 한다

        MvcResult 결과 = mvc.perform(캘린더요청(f.propertyId(), "2027-10-10", "2027-10-20")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andReturn();

        var reservations = json.readTree(결과.getResponse().getContentAsString()).get("reservations");
        assertThat(reservations.size())
                .as("겹치는 3건만 나와야 한다")
                .isEqualTo(3);
    }

    // --- 완료 조건 6 ---------------------------------------------------------

    @Test
    @DisplayName("응답 어디에도 전화번호와 이메일이 없다")
    void 응답에_연락처가_없다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        예약등록(f, "2027-11-01", "2027-11-05");

        MvcResult 결과 = mvc.perform(캘린더요청(f.propertyId(), "2027-11-01", "2027-11-10")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isOk())
                .andReturn();

        String 응답 = 결과.getResponse().getContentAsString();

        assertThat(응답)
                .as("연락처가 화면 응답으로 새면 암호화를 우회하는 경로가 하나 더 생긴다")
                .doesNotContain("01055556666")
                .doesNotContain(전화번호)
                .doesNotContain(이메일);
        // 이름까지는 담는다. guest.name 은 평문 컬럼이라 새로 새는 것이 없다.
        assertThat(응답).contains("홍길동");
    }

    // --- 그 밖의 조립 규칙 ---------------------------------------------------

    @Test
    void 충돌은_아직_항상_false다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));

        var days = 날짜별셀(f, "2027-12-01", "2027-12-03");

        // overbooking_conflict 에 행을 만드는 경로가 P3 에 생긴다. 필드는 지금부터 있다.
        days.forEach((date, cell) -> assertThat(cell.get("conflict").asBoolean()).isFalse());
    }

    @Test
    void 판매_단위가_없으면_빈_그리드를_돌려준다() throws Exception {
        Session session = 가입(새이메일());
        Long propertyId = 숙소만(session);

        mvc.perform(캘린더요청(propertyId, "2028-01-01", "2028-01-10")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units.length()").value(0))
                .andExpect(jsonPath("$.reservations.length()").value(0));
    }

    @Test
    void 취소된_예약은_막대로_그리지_않는다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        Long reservationId = 예약등록(f, "2028-02-01", "2028-02-05");
        mvc.perform(post("/api/reservations/" + reservationId + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, f.session().bearer()));

        MvcResult 결과 = mvc.perform(캘린더요청(f.propertyId(), "2028-02-01", "2028-02-10")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andReturn();

        assertThat(json.readTree(결과.getResponse().getContentAsString())
                .get("reservations").size()).isZero();
    }

    // --- 헬퍼 ---------------------------------------------------------------

    private record Fixture(Session session, Long propertyId, Long unitId) {
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            캘린더요청(Long propertyId, String from, String to) {
        return get("/api/properties/" + propertyId + "/calendar")
                .param("from", from)
                .param("to", to);
    }

    /** 날짜 문자열로 첫 판매 단위의 셀을 찾아 쓰기 쉽게 만든다. */
    private java.util.Map<String, com.fasterxml.jackson.databind.JsonNode>
            날짜별셀(Fixture f, String from, String to) throws Exception {
        MvcResult 결과 = mvc.perform(캘린더요청(f.propertyId(), from, to)
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isOk())
                .andReturn();

        var days = json.readTree(결과.getResponse().getContentAsString())
                .get("units").get(0).get("days");
        var byDate = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        days.forEach(day -> byDate.put(day.get("date").asText(), day));
        return byDate;
    }

    private Fixture 숙소와_판매단위(Session session) throws Exception {
        return 숙소와_판매단위(session, 1);
    }

    private Fixture 숙소와_판매단위(Session session, int totalUnits) throws Exception {
        Long propertyId = 숙소만(session);
        MvcResult unit = mvc.perform(post("/api/properties/" + propertyId + "/units")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"본채","unitKind":"ENTIRE_PLACE","totalUnits":%d,"basePrice":80000}
                                """.formatted(totalUnits)))
                .andExpect(status().isCreated())
                .andReturn();
        Long unitId = json.readTree(unit.getResponse().getContentAsString()).get("id").asLong();
        return new Fixture(session, propertyId, unitId);
    }

    private Long 숙소만(Session session) throws Exception {
        MvcResult property = mvc.perform(post("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"캘린더 숙소","address":"서울"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(property.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long 예약등록(Fixture f, String checkIn, String checkOut) throws Exception {
        MvcResult result = mvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyId":%d,"unitId":%d,"checkIn":"%s","checkOut":"%s",
                                 "totalAmount":300000,"adults":2,"children":0,
                                 "guestName":"홍길동","guestPhone":"%s","guestEmail":"%s"}
                                """.formatted(f.propertyId(), f.unitId(), checkIn, checkOut,
                                전화번호, 이메일)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long 기본요금제(Long unitId) {
        return jdbc.queryForObject(
                "SELECT id FROM rate_plan WHERE unit_id = ? AND is_default", Long.class, unitId);
    }

    /** 요금 편집 API 는 9주차다. 지금은 직접 넣는다. */
    private void 요금설정(Long ratePlanId, String date, int price, int minStay) {
        jdbc.update("""
                INSERT INTO rate_calendar (rate_plan_id, stay_date, price, min_stay)
                VALUES (?, ?::date, ?, ?)
                """, ratePlanId, date, price, minStay);
    }

    private static String 새이메일() {
        return "cal-" + UUID.randomUUID() + "@example.com";
    }
}
