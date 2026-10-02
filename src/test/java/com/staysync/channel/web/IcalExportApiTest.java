package com.staysync.channel.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.staysync.shared.time.ServiceZone;
import com.staysync.support.ApiTestBase;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * <b>완료 조건 8·9·10·11.</b> iCal 발행.
 *
 * <p>가장 중요한 것은 9번과 10번이다. {@code DTEND} 를 포함으로 쓰면 받을 때
 * 배타적으로 읽는 것과 어긋나 <b>왕복에서 하루가 밀리고 화면은 정상으로 보인다.</b>
 * 게스트 이름은 인증이 URL 하나뿐인 응답에 실리면 그대로 유출이다.
 */
class IcalExportApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    private static final DateTimeFormatter ICAL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 오늘 기준으로 잡는다. 발행 구간이 오늘부터라 고정 날짜를 쓰면 언젠가 지나간다. */
    private static final LocalDate 체크인 = LocalDate.now().plusDays(30);
    private static final LocalDate 체크아웃 = 체크인.plusDays(3);

    @Test
    @DisplayName("확정 예약이 VEVENT 로 나오고 DTEND 가 체크아웃일 그대로다")
    void 예약이_배타적_DTEND_로_나온다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long unitId = 판매단위등록(세션, propertyId);
        String url = 발행URL(세션, propertyId, unitId);
        예약등록(세션, propertyId, unitId, 체크인, 체크아웃);

        String ics = 발행물(url);

        assertThat(ics).contains("BEGIN:VEVENT");
        assertThat(ics).contains("DTSTART;VALUE=DATE:" + 체크인.format(ICAL_DATE));
        // 3박이면 막힌 밤이 셋이고 그 다음 날이 체크아웃일이다. 그 값이 그대로 DTEND 다.
        // 하루를 더하거나 빼면 왕복에서 어긋난다.
        assertThat(ics)
                .as("DTEND 는 배타적이다. 체크아웃일을 그대로 쓴다")
                .contains("DTEND;VALUE=DATE:" + 체크아웃.format(ICAL_DATE));
        assertThat(ics).doesNotContain("DTEND;VALUE=DATE:" + 체크아웃.plusDays(1).format(ICAL_DATE));
        assertThat(ics).doesNotContain("DTEND;VALUE=DATE:" + 체크아웃.minusDays(1).format(ICAL_DATE));
    }

    @Test
    @DisplayName("판매 중지 구간도 VEVENT 로 나온다")
    void 판매중지도_나온다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long unitId = 판매단위등록(세션, propertyId);
        String url = 발행URL(세션, propertyId, unitId);

        LocalDate 중지시작 = LocalDate.now().plusDays(100);
        판매중지(세션, propertyId, unitId, 중지시작, 중지시작.plusDays(1));

        String ics = 발행물(url);

        assertThat(ics).contains("DTSTART;VALUE=DATE:" + 중지시작.format(ICAL_DATE));
        // 일괄 편집은 두 날짜를 포함으로 다룬다. 막힌 마지막 날의 다음 날이 DTEND 다.
        assertThat(ics).contains("DTEND;VALUE=DATE:" + 중지시작.plusDays(2).format(ICAL_DATE));
    }

    @Test
    @DisplayName("서버의 오늘이 KST 의 오늘과 달라도 발행은 KST 오늘부터다")
    void 발행_구간은_KST_오늘부터다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long unitId = 판매단위등록(세션, propertyId);
        String url = 발행URL(세션, propertyId, unitId);

        // KST 어제와 오늘을 막는다. 지난 날은 API 로 못 막으므로 원장에 직접 쓴다.
        LocalDate 오늘 = ServiceZone.today();
        for (LocalDate d : new LocalDate[] {오늘.minusDays(1), 오늘}) {
            jdbc.update("""
                    INSERT INTO inventory_ledger (unit_id, stay_date, total_units, stop_sell)
                    VALUES (?, ?, 1, true)
                    ON CONFLICT (unit_id, stay_date) DO UPDATE SET stop_sell = true""", unitId, d);
        }

        // JVM 의 오늘을 KST 의 오늘과 다른 날로 만든다. 배포(UTC)에서 KST 새벽에 생기는 일이다.
        // UTC-12 는 KST 21시 전까지 하루 늦고, 그 뒤에는 UTC+14 가 하루 이르다 — 언제 돌려도 날짜가 갈린다.
        java.util.TimeZone 원래 = java.util.TimeZone.getDefault();
        String 서버 = java.time.LocalTime.now(ServiceZone.SEOUL).getHour() < 21 ? "Etc/GMT+12" : "Pacific/Kiritimati";
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(서버));
        String ics;
        try {
            assertThat(LocalDate.now()).as("전제 — JVM 의 오늘이 KST 와 다르다").isNotEqualTo(오늘);
            ics = 발행물(url);
        } finally {
            java.util.TimeZone.setDefault(원래);
        }

        // 서버가 하루 늦으면 어제부터, 하루 이르면 오늘이 빠진다. KST 오늘에서 시작해야 맞다.
        assertThat(ics).contains("DTSTART;VALUE=DATE:" + 오늘.format(ICAL_DATE));
        assertThat(ics).doesNotContain("DTSTART;VALUE=DATE:" + 오늘.minusDays(1).format(ICAL_DATE));
    }

    @Test
    @DisplayName("발행물 어디에도 게스트 이름이 없다")
    void 게스트_이름이_새지_않는다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long unitId = 판매단위등록(세션, propertyId);
        String url = 발행URL(세션, propertyId, unitId);
        예약등록(세션, propertyId, unitId, 체크인, 체크아웃);

        String ics = 발행물(url);

        // 이 응답은 인증이 URL 하나뿐이고 읽는 쪽은 에어비앤비 서버다. 문자열로 훑는다.
        assertThat(ics)
                .doesNotContain("홍길동")
                // 번호 전체로 본다. 앞 세 자리만 보면 날짜와 부딪힌다 —
                // 체크아웃이 10일이면 DTEND 의 "20261010" 이 "010" 을 담는다.
                .doesNotContain("01012345678")
                .doesNotContain("guest")
                .doesNotContain("Guest");
        assertThat(ics).contains("SUMMARY:StaySync (Not available)");
        // 토큰 자신도 발행물에 나타나면 안 된다. UID 의 도메인 자리가 그 자리다.
        assertThat(ics).doesNotContain(url.substring(url.lastIndexOf('/') + 1, url.length() - 4));
    }

    @Test
    @DisplayName("토큰이 틀리면 404 다. 존재를 알리지 않는다")
    void 틀린_토큰은_404_다() throws Exception {
        // 401 이나 403 으로 답하면 "그 토큰은 있다"를 알려 주는 셈이 되어
        // 대입으로 유효한 토큰을 좁힐 수 있다.
        mvc.perform(get("/public/ical/deadbeefdeadbeefdeadbeefdeadbeef.ics"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("발행 URL 은 전용 경로에서만 나오고 매핑 목록에는 없다")
    void 토큰이_목록_응답에_실리지_않는다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long unitId = 판매단위등록(세션, propertyId);
        String url = 발행URL(세션, propertyId, unitId);
        String token = url.substring(url.lastIndexOf('/') + 1, url.length() - 4);

        // 화면을 그릴 때마다 오가는 응답에 실리면 브라우저 캐시와 프록시 로그
        // 어디에나 남는다. 새는 쪽에서는 아무 증상이 없다.
        assertThat(본문(get("/api/channels"), 세션)).doesNotContain(token);
        assertThat(본문(get("/api/channels/" + connectionId(세션) + "/mappings"), 세션))
                .doesNotContain(token);
    }

    @Test
    @DisplayName("연결을 끄면 발행이 404 가 된다")
    void 꺼진_연결은_발행하지_않는다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long unitId = 판매단위등록(세션, propertyId);
        String url = 발행URL(세션, propertyId, unitId);
        assertThat(발행물(url)).contains("BEGIN:VCALENDAR");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/channels/" + connectionId(세션))
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncEnabled\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get(경로(url))).andExpect(status().isNotFound());
    }

    // --- 픽스처 -------------------------------------------------------------

    private Long lastConnectionId;

    private Long connectionId(Session 세션) {
        return lastConnectionId;
    }

    private String 발행물(String url) throws Exception {
        return mvc.perform(get(경로(url)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String 경로(String url) {
        return url.substring(url.indexOf("/public/"));
    }

    private String 본문(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder 요청,
                      Session 세션) throws Exception {
        return mvc.perform(요청.header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 연결과 매핑을 만들고 발행 URL 을 받아 온다. */
    private String 발행URL(Session 세션, Long propertyId, Long unitId) throws Exception {
        MvcResult 연결 = mvc.perform(post("/api/properties/" + propertyId + "/channels")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelCode":"AIRBNB_ICAL","adapterType":"ICAL",
                                 "displayName":"에어비앤비",
                                 "credentials":{"ical_url":"https://example.com/x.ics"}}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        lastConnectionId = json.readTree(연결.getResponse().getContentAsString()).get("id").asLong();

        MvcResult 매핑 = mvc.perform(post("/api/channels/" + lastConnectionId + "/mappings")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":%d,"externalUnitId":"listing-1"}
                                """.formatted(unitId)))
                .andExpect(status().isCreated())
                .andReturn();
        Long mappingId = json.readTree(매핑.getResponse().getContentAsString()).get("id").asLong();

        MvcResult 발행 = mvc.perform(get("/api/channels/" + lastConnectionId
                        + "/mappings/" + mappingId + "/export-url")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andReturn();
        return json.readTree(발행.getResponse().getContentAsString()).get("url").asText();
    }

    private Long 숙소등록(Session 세션) throws Exception {
        MvcResult result = mvc.perform(post("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"발행 테스트 숙소\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long 판매단위등록(Session 세션, Long propertyId) throws Exception {
        MvcResult result = mvc.perform(post("/api/properties/" + propertyId + "/units")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"본채","unitKind":"ENTIRE_PLACE","totalUnits":1,
                                 "basePrice":100000}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private void 예약등록(Session 세션, Long propertyId, Long unitId,
                     LocalDate checkIn, LocalDate checkOut) throws Exception {
        mvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyId":%d,"unitId":%d,"checkIn":"%s","checkOut":"%s",
                                 "adults":2,"children":0,"totalAmount":300000,
                                 "guestName":"홍길동","guestPhone":"01012345678"}
                                """.formatted(propertyId, unitId, checkIn, checkOut)))
                .andExpect(status().isCreated());
    }

    private void 판매중지(Session 세션, Long propertyId, Long unitId,
                     LocalDate from, LocalDate to) throws Exception {
        mvc.perform(post("/api/properties/" + propertyId + "/calendar/bulk-edit")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitIds":[%d],"from":"%s","to":"%s","stopSell":true}
                                """.formatted(unitId, from, to)))
                .andExpect(status().isOk());
    }

    private static String 새이메일() {
        return "ical-export-" + UUID.randomUUID() + "@example.com";
    }
}
