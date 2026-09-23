package com.staysync.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.staysync.support.ApiTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** 완료 조건 6. 예약 API 와 조직 스코핑. */
class ReservationApiTest extends ApiTestBase {

    @Test
    void 수기_예약을_등록하면_목록에서_조회된다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));

        Long reservationId = 예약등록(f, "2027-03-01", "2027-03-03", "200000");

        mvc.perform(get("/api/reservations").header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(reservationId))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].channelCode").value("DIRECT"))
                .andExpect(jsonPath("$[0].nights").value(2));
    }

    @Test
    @DisplayName("기간으로 거르면 200 이고 겹치는 예약만 온다")
    void 기간으로_거른다() throws Exception {
        // 작업지시-18 C. `(:from is null or r.period.checkOut > :from)` 의 형 추론이 실패해
        // from/to 를 주면 500 이었다. 화면이 안 부르는 경로라 증상이 없었다(확인-08 3절 3번).
        // 리포지토리 단위로는 이 결함이 난 자리(HTTP → 파라미터 바인딩)를 안 지난다.
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        Long 삼월 = 예약등록(f, "2027-03-01", "2027-03-03", "200000");
        Long 오월 = 예약등록(f, "2027-05-10", "2027-05-12", "200000");

        mvc.perform(get("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                        .param("from", "2027-03-02").param("to", "2027-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(삼월));

        // 경계: 체크아웃이 from 과 같으면 그날 묵지 않으므로 빠진다. 체크인이 to 와 같아도 빠진다.
        mvc.perform(get("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                        .param("from", "2027-03-03").param("to", "2027-05-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 한쪽만 줘도 된다.
        mvc.perform(get("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                        .param("from", "2027-05-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(오월));
    }

    @Test
    @DisplayName("상세에는 박별 스냅샷이 실린다")
    void 상세에_박별_요금이_실린다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        Long reservationId = 예약등록(f, "2027-04-01", "2027-04-04", "100000");

        mvc.perform(get("/api/reservations/" + reservationId)
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation.id").value(reservationId))
                .andExpect(jsonPath("$.nights.length()").value(3))
                .andExpect(jsonPath("$.guestName").value("홍길동"));
    }

    @Test
    void 취소하고_다시_취소해도_200이다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        Long reservationId = 예약등록(f, "2027-05-01", "2027-05-03", "100000");

        mvc.perform(post("/api/reservations/" + reservationId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mvc.perform(post("/api/reservations/" + reservationId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void 체크인과_체크아웃이_순서대로_된다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        Long reservationId = 예약등록(f, "2027-06-01", "2027-06-03", "100000");

        mvc.perform(post("/api/reservations/" + reservationId + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));

        mvc.perform(post("/api/reservations/" + reservationId + "/check-out")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"));
    }

    @Test
    void 체크인_취소와_체크아웃_되돌리기는_사유가_있어야_한다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        Long reservationId = 예약등록(f, "2027-06-10", "2027-06-12", "100000");
        mvc.perform(post("/api/reservations/" + reservationId + "/check-in")
                .header(HttpHeaders.AUTHORIZATION, f.session().bearer()));

        mvc.perform(post("/api/reservations/" + reservationId + "/check-in/undo")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/reservations/" + reservationId + "/check-in/undo")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"잘못 눌렀다\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mvc.perform(post("/api/reservations/" + reservationId + "/check-in")
                .header(HttpHeaders.AUTHORIZATION, f.session().bearer()));
        mvc.perform(post("/api/reservations/" + reservationId + "/check-out")
                .header(HttpHeaders.AUTHORIZATION, f.session().bearer()));
        mvc.perform(post("/api/reservations/" + reservationId + "/check-out/undo")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"손님이 아직 있다\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));
    }

    @Test
    void 남의_조직_예약은_되돌릴_수_없다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        Long reservationId = 예약등록(f, "2027-06-20", "2027-06-22", "100000");
        mvc.perform(post("/api/reservations/" + reservationId + "/check-in")
                .header(HttpHeaders.AUTHORIZATION, f.session().bearer()));
        Fixture other = 숙소와_판매단위(가입(새이메일()));

        mvc.perform(post("/api/reservations/" + reservationId + "/check-in/undo")
                        .header(HttpHeaders.AUTHORIZATION, other.session().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"사유\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 체크아웃된_예약을_취소하면_400이_나간다_전이오류() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        Long reservationId = 예약등록(f, "2027-07-01", "2027-07-03", "100000");
        mvc.perform(post("/api/reservations/" + reservationId + "/check-in")
                .header(HttpHeaders.AUTHORIZATION, f.session().bearer()));
        mvc.perform(post("/api/reservations/" + reservationId + "/check-out")
                .header(HttpHeaders.AUTHORIZATION, f.session().bearer()));

        mvc.perform(post("/api/reservations/" + reservationId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ILLEGAL_RESERVATION_TRANSITION"));
    }

    @Test
    void 날짜를_변경할_수_있다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        Long reservationId = 예약등록(f, "2027-08-01", "2027-08-03", "100000");

        mvc.perform(patch("/api/reservations/" + reservationId)
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkIn":"2027-08-10","checkOut":"2027-08-12"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkIn").value("2027-08-10"));
    }

    @Test
    void 재고가_모자라면_409로_거절된다() throws Exception {
        Fixture f = 숙소와_판매단위(가입(새이메일()));
        예약등록(f, "2027-09-01", "2027-09-03", "100000");

        mvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(예약본문(f, "2027-09-01", "2027-09-03", "100000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_SOLD_OUT"));
    }

    // --- 완료 조건 6: 조직 스코핑 ---------------------------------------------

    @Test
    @DisplayName("다른 조직의 예약은 조회되지 않는다")
    void 다른_조직의_예약은_조회되지_않는다() throws Exception {
        Fixture 갑 = 숙소와_판매단위(가입(새이메일()));
        Session 을 = 가입(새이메일());
        Long 갑의예약 = 예약등록(갑, "2027-10-01", "2027-10-03", "100000");

        // 을의 목록은 비어 있다
        mvc.perform(get("/api/reservations").header(HttpHeaders.AUTHORIZATION, 을.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 식별자를 직접 넣어도 404 다. 403 이면 존재 여부가 새어 나간다.
        mvc.perform(get("/api/reservations/" + 갑의예약)
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("다른 조직의 예약은 변경할 수 없다")
    void 다른_조직의_예약은_변경할_수_없다() throws Exception {
        Fixture 갑 = 숙소와_판매단위(가입(새이메일()));
        Session 을 = 가입(새이메일());
        Long 갑의예약 = 예약등록(갑, "2027-11-01", "2027-11-03", "100000");

        mvc.perform(patch("/api/reservations/" + 갑의예약)
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkIn":"2027-11-05","checkOut":"2027-11-07"}
                                """))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/reservations/" + 갑의예약 + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/reservations/" + 갑의예약 + "/check-in")
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer()))
                .andExpect(status().isNotFound());

        // 갑의 예약은 그대로 살아 있어야 한다
        mvc.perform(get("/api/reservations/" + 갑의예약)
                        .header(HttpHeaders.AUTHORIZATION, 갑.session().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("다른 조직의 숙소에는 예약을 만들 수 없다")
    void 다른_조직의_숙소에는_예약을_만들_수_없다() throws Exception {
        Fixture 갑 = 숙소와_판매단위(가입(새이메일()));
        Session 을 = 가입(새이메일());

        mvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(예약본문(갑, "2027-12-01", "2027-12-03", "100000")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_NOT_FOUND"));
    }

    @Test
    void 토큰_없이_예약을_부르면_401이다() throws Exception {
        mvc.perform(get("/api/reservations")).andExpect(status().isUnauthorized());
    }

    // --- 헬퍼 ---------------------------------------------------------------

    private record Fixture(Session session, Long propertyId, Long unitId) {
    }

    private Fixture 숙소와_판매단위(Session session) throws Exception {
        MvcResult property = mvc.perform(post("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"예약 API 숙소","address":"서울"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        Long propertyId = json.readTree(property.getResponse().getContentAsString())
                .get("id").asLong();

        MvcResult unit = mvc.perform(post("/api/properties/" + propertyId + "/units")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"본채","unitKind":"ENTIRE_PLACE","totalUnits":1,"basePrice":100000}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        Long unitId = json.readTree(unit.getResponse().getContentAsString()).get("id").asLong();

        return new Fixture(session, propertyId, unitId);
    }

    private Long 예약등록(Fixture f, String checkIn, String checkOut, String amount) throws Exception {
        MvcResult result = mvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, f.session().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(예약본문(f, checkIn, checkOut, amount)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private String 예약본문(Fixture f, String checkIn, String checkOut, String amount) {
        return """
                {"propertyId":%d,"unitId":%d,"checkIn":"%s","checkOut":"%s",
                 "totalAmount":%s,"adults":2,"children":0,
                 "guestName":"홍길동","guestPhone":"010-1234-5678","guestEmail":"g@example.com"}
                """.formatted(f.propertyId(), f.unitId(), checkIn, checkOut, amount);
    }

    private static String 새이메일() {
        return "book-" + UUID.randomUUID() + "@example.com";
    }
}
