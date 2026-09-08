package com.staysync.booking.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.staysync.support.ApiTestBase;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 작업지시 13 의 완료 조건 4·5·6. 직접예약 위젯의 공개 경로.
 *
 * <p><b>6번이 이 파일의 요점이다.</b> 화면이 보낸 금액을 그대로 쓰면 결제 금액과 예약
 * 금액이 다른 예약이 생기고, <b>화면에는 아무 이상이 없다.</b> P4 부터 "틀렸다"의
 * 뜻이 바뀐다고 적어 둔 것이 이번에는 돈이다.
 *
 * <p>이 경로는 {@code OwnedResources} 를 거치지 않는 유일한 쓰기다. 조직 스코핑을 걸
 * 수 없으므로 그 몫을 값 검증이 대신한다.
 */
class PublicBookingApiTest extends ApiTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    private com.staysync.booking.HoldExpiryJob holdExpiryJob;

    /** 인원이 실제로 저장됐는지는 응답에 없다. 행을 직접 본다. */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static final LocalDate 체크인 = LocalDate.now().plusDays(40);
    private static final LocalDate 체크아웃 = 체크인.plusDays(2);

    // --- 완료 조건 4 ---------------------------------------------------------

    @Test
    @DisplayName("로그인 없이 가용과 요금을 볼 수 있다")
    void 토큰_없이_열린다() throws Exception {
        Fixture f = 숙소하나();

        mvc.perform(get("/public/booking/" + f.propertyId() + "/availability")
                        .param("from", 체크인.toString())
                        .param("to", 체크아웃.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(f.unitId()))
                .andExpect(jsonPath("$[0].days[0].price").value(100000));
    }

    @Test
    @DisplayName("공개 응답에 게스트 정보가 실리지 않는다")
    void 게스트_정보가_새지_않는다() throws Exception {
        Fixture f = 숙소하나();
        홀드(f, 200_000, "홍길동");

        MvcResult result = mvc.perform(get("/public/booking/" + f.propertyId() + "/availability")
                        .param("from", 체크인.toString())
                        .param("to", 체크아웃.toString()))
                .andExpect(status().isOk())
                .andReturn();

        // 조립은 캘린더와 같은 경로를 쓰지만 그쪽 응답에는 예약 막대가 들어 있다.
        // 공개 경계에서 날짜별 수량과 요금만 남는지 문자열로 훑는다.
        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("홍길동")
                .doesNotContain("reservation")
                .doesNotContain("guest");
    }

    // --- 완료 조건 5 ---------------------------------------------------------

    @Test
    @DisplayName("가용하지 않은 날짜로는 HOLD 가 만들어지지 않는다")
    void 팔_수_없는_날은_거절한다() throws Exception {
        Fixture f = 숙소하나();
        // 1실짜리다. 첫 홀드가 그 날짜를 다 차지한다.
        홀드(f, 200_000, "먼저 온 손님").andExpect(status().isCreated());

        홀드(f, 200_000, "나중 손님")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATE_UNAVAILABLE"));
    }

    // --- 완료 조건 6 ---------------------------------------------------------

    @Test
    @DisplayName("화면이 보낸 금액이 서버 재계산과 다르면 거절한다")
    void 금액이_다르면_거절한다() throws Exception {
        Fixture f = 숙소하나();

        // 2박 × 100,000 = 200,000 이다. 화면이 10만원을 들고 왔다.
        홀드(f, 100_000, "값을 조작한 손님")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_MISMATCH"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("200000")));
    }

    @Test
    @DisplayName("금액이 맞으면 홀드가 만들어지고 만료 시각이 실린다")
    void 금액이_맞으면_홀드가_생긴다() throws Exception {
        Fixture f = 숙소하나();

        홀드(f, 200_000, "정상 손님")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(200000))
                .andExpect(jsonPath("$.confirmationCode").isNotEmpty())
                // 15분 창이 언제 닫히는지 화면이 알아야 결제 시간을 보여 줄 수 있다.
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("위젯이 고른 인원이 예약에 그대로 남는다")
    void 인원이_예약에_남는다() throws Exception {
        Fixture f = 숙소하나();

        홀드(f, 200_000, "네 식구").andExpect(status().isCreated());

        // 넘기지 않으면 예약 기본값(성인 2, 아동 0)이 박힌다. 그러면 호스트가 보는
        // 인원이 늘 2명이고 화면에는 아무 이상이 없다 — 청소와 정원 판단이 거기 걸린다.
        // 홀드() 가 성인 2 아동 1 로 보낸다.
        java.util.Map<String, Object> 예약 = jdbc.queryForMap(
                "SELECT adults, children FROM reservation WHERE unit_id = ? AND status = 'HOLD'",
                f.unitId());
        org.assertj.core.api.Assertions.assertThat(((Number) 예약.get("adults")).intValue())
                .isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(((Number) 예약.get("children")).intValue())
                .as("아동 수가 떨어지면 기본값 0 과 구분되지 않는다")
                .isEqualTo(1);
    }

    // --- 완료 조건 7 ---------------------------------------------------------

    @Test
    @DisplayName("홀드가 만료되면 그 날짜를 다시 팔 수 있다")
    void 만료되면_재고가_돌아온다() throws Exception {
        Fixture f = 숙소하나();
        홀드(f, 200_000, "결제를 안 한 손님").andExpect(status().isCreated());

        // 1실짜리라 홀드가 그 날짜를 다 차지한다.
        홀드(f, 200_000, "두 번째 손님").andExpect(status().isConflict());

        // 만료 배치를 15분 뒤 시점으로 돌린다. 위젯이 새로 만들지 않고 4주차의
        // HoldExpiryJob 을 그대로 쓴다 — 만료 경로가 둘이 되면 갈라진다.
        holdExpiryJob.expireDueHolds(java.time.OffsetDateTime.now().plusMinutes(16));

        // 만료로 재고가 돌아오지 않으면 그 방은 결제를 안 한 손님 때문에 계속 막힌다.
        홀드(f, 200_000, "만료 뒤에 온 손님").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("예약자 이름이 없으면 거절한다")
    void 이름이_없으면_거절한다() throws Exception {
        Fixture f = 숙소하나();

        mvc.perform(post("/public/booking/" + f.propertyId() + "/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":%d,"checkIn":"%s","checkOut":"%s",
                                 "quotedAmount":200000,"adults":2,"children":0,"guestName":""}
                                """.formatted(f.unitId(), 체크인, 체크아웃)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("남의 숙소의 판매 단위를 끼워 넣을 수 없다")
    void 남의_판매_단위는_넣을_수_없다() throws Exception {
        Fixture 내것 = 숙소하나();
        Fixture 남의것 = 숙소하나();

        mvc.perform(post("/public/booking/" + 내것.propertyId() + "/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":%d,"checkIn":"%s","checkOut":"%s",
                                 "quotedAmount":200000,"adults":2,"children":0,"guestName":"끼워넣기"}
                                """.formatted(남의것.unitId(), 체크인, 체크아웃)))
                .andExpect(status().isNotFound());
    }

    // --- 픽스처 ---------------------------------------------------------------

    private record Fixture(Long propertyId, Long unitId) {
    }

    private org.springframework.test.web.servlet.ResultActions 홀드(
            Fixture f, int amount, String guestName) throws Exception {
        return mvc.perform(post("/public/booking/" + f.propertyId() + "/hold")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"unitId":%d,"checkIn":"%s","checkOut":"%s",
                         "quotedAmount":%d,"adults":2,"children":1,"guestName":"%s","guestPhone":"01012345678"}
                        """.formatted(f.unitId(), 체크인, 체크아웃, amount, guestName)));
    }

    private static String 새이메일() {
        return "widget-" + java.util.UUID.randomUUID() + "@example.com";
    }

    /** 숙소와 1실짜리 판매 단위 하나. 기본 요금 10만원이다. */
    private Fixture 숙소하나() throws Exception {
        Session 세션 = 가입(새이메일());
        MvcResult property = mvc.perform(post("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"위젯 테스트 숙소\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Long propertyId = json.readTree(property.getResponse().getContentAsString())
                .get("id").asLong();

        MvcResult unit = mvc.perform(post("/api/properties/" + propertyId + "/units")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"본채","unitKind":"ENTIRE_PLACE","totalUnits":1,
                                 "basePrice":100000}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        Long unitId = json.readTree(unit.getResponse().getContentAsString()).get("id").asLong();

        return new Fixture(propertyId, unitId);
    }
}
