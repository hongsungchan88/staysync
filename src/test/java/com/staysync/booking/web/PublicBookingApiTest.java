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
                .andExpect(jsonPath("$.units[0].id").value(f.unitId()))
                .andExpect(jsonPath("$.units[0].days[0].price").value(100000))
                // 공개 페이지라 어느 숙소인지 화면에서 확인할 수 있어야 한다.
                .andExpect(jsonPath("$.propertyName").value("위젯 테스트 숙소"));
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
                        .header("X-Forwarded-For", 내것.ip())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":%d,"checkIn":"%s","checkOut":"%s",
                                 "quotedAmount":200000,"adults":2,"children":0,"guestName":"끼워넣기"}
                                """.formatted(남의것.unitId(), 체크인, 체크아웃)))
                .andExpect(status().isNotFound());
    }

    // --- 작업지시-18 D. 속도 제한 (완료 조건 9·10) ---------------------------------

    @Test
    @DisplayName("한 IP 가 창 안에서 상한을 넘으면 429 이고 HOLD 가 생기지 않는다. 다른 IP 는 그대로 된다")
    void 상한을_넘으면_429_이고_다른_IP_는_된다() throws Exception {
        // 속도 제한은 서비스 앞이라 결과(201/409)와 무관하게 요청을 센다 — 날짜를 바꿔
        // 가며 잡는 손님과 스크립트가 같은 모양이기 때문이다. 재고를 넉넉히 둬서 다섯
        // 번째까지 실제로 잡히게 한다.
        Fixture f = 숙소하나((short) 9);
        for (int i = 1; i <= 5; i++) {
            홀드(f, 200_000, "손님" + i).andExpect(status().isCreated());
        }
        long 전 = 홀드_수(f);

        홀드(f, 200_000, "여섯번째")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("HOLD_RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("예약 요청이 너무 잦습니다. 잠시 뒤 다시 시도해 주세요."));

        org.assertj.core.api.Assertions.assertThat(홀드_수(f))
                .as("거절된 요청은 HOLD 를 만들지 않는다")
                .isEqualTo(전);

        // 다른 손님(다른 IP)은 그 사이에도 된다. 전역 차단이 아니다.
        mvc.perform(post("/public/booking/" + f.propertyId() + "/hold")
                        .header("X-Forwarded-For", "203.0.113.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(홀드본문(f, "다른 손님")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("X-Forwarded-For 가 있으면 그 주소로 가르고, 없으면 접속 주소로 센다")
    void 전달_헤더로_IP_를_가른다() throws Exception {
        // Caddy 뒤에서는 모든 요청의 접속 주소가 Caddy 하나다. 전달 헤더를 안 풀면 한 사람이
        // 다섯 번 누른 뒤 모든 손님이 막힌다(작업지시-18 4절). 헤더가 없는 요청(로컬 직접
        // 접속)은 접속 주소로 센다 — 위조가 아니라 프록시가 없는 것이다.
        Fixture f = 숙소하나((short) 9);
        String 프록시 = "172.19.0.4";   // Caddy 컨테이너 같은 내부 주소

        for (int i = 1; i <= 5; i++) {
            프록시를_거친_홀드(f, 프록시, "203.0.113.1", "첫 손님 " + i).andExpect(status().isCreated());
        }
        // 같은 프록시를 거쳤지만 다른 손님이다.
        프록시를_거친_홀드(f, 프록시, "203.0.113.2", "둘째 손님").andExpect(status().isCreated());
        // 첫 손님은 막힌다.
        프록시를_거친_홀드(f, 프록시, "203.0.113.1", "첫 손님 여섯 번째")
                .andExpect(status().isTooManyRequests());
        // 헤더가 없으면 접속 주소(프록시 주소 자체)로 센다. 아직 한 번도 안 셌으니 된다.
        프록시를_거친_홀드(f, 프록시, null, "헤더 없는 손님").andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions 프록시를_거친_홀드(
            Fixture f, String remoteAddr, String forwardedFor, String guestName) throws Exception {
        var request = post("/public/booking/" + f.propertyId() + "/hold")
                .with(r -> { r.setRemoteAddr(remoteAddr); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(홀드본문(f, guestName));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mvc.perform(request);
    }

    private String 홀드본문(Fixture f, String guestName) {
        return """
                {"unitId":%d,"checkIn":"%s","checkOut":"%s",
                 "quotedAmount":200000,"adults":2,"children":0,"guestName":"%s"}
                """.formatted(f.unitId(), 체크인, 체크아웃, guestName);
    }

    private long 홀드_수(Fixture f) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE property_id = ? AND status = 'HOLD'",
                Long.class, f.propertyId());
    }

    // --- 픽스처 ---------------------------------------------------------------

    /**
     * @param ip 이 손님의 IP. 배포 환경에서는 Caddy 가 {@code X-Forwarded-For} 로 붙이고
     *           {@code forward-headers-strategy: framework} 가 {@code getRemoteAddr()} 로
     *           풀어 준다. 픽스처마다 다르게 준다 — 전부 MockMvc 기본값(127.0.0.1)이면
     *           이 파일의 홀드 열한 번이 한 IP 로 세어져 여섯 번째부터 429 다
     */
    private record Fixture(Long propertyId, Long unitId, String ip) {
    }

    private org.springframework.test.web.servlet.ResultActions 홀드(
            Fixture f, int amount, String guestName) throws Exception {
        return mvc.perform(post("/public/booking/" + f.propertyId() + "/hold")
                .header("X-Forwarded-For", f.ip())
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
        return 숙소하나((short) 1);
    }

    private Fixture 숙소하나(short totalUnits) throws Exception {
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
                                {"name":"본채","unitKind":"ENTIRE_PLACE","totalUnits":%d,
                                 "basePrice":100000}
                                """.formatted(totalUnits)))
                .andExpect(status().isCreated())
                .andReturn();
        Long unitId = json.readTree(unit.getResponse().getContentAsString()).get("id").asLong();

        // 문서용 대역(TEST-NET-2). 숙소 식별자로 갈라 픽스처마다 다른 손님이 된다.
        return new Fixture(propertyId, unitId,
                "198.51." + (propertyId / 250 % 256) + "." + (propertyId % 250 + 1));
    }
}
