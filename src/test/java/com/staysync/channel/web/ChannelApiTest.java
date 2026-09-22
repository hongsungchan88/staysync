package com.staysync.channel.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.staysync.channel.ChannelCredentialStore;
import com.staysync.support.ApiTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 완료 조건 3~9. 채널 연결과 매핑 API.
 *
 * <p>이 파일에서 가장 중요한 것은 자격 증명이 새지 않는지 보는 세 가지(3·4·7)다.
 * 자격 증명이 새도 기능은 정상으로 보이고, 드러나는 것은 그 사람의 채널 계정이
 * 열린 뒤다. 5~6주차 게스트 연락처에서 한 것과 같은 검증이다.
 */
class ChannelApiTest extends ApiTestBase {

    /** 마스킹이 양끝 네 글자만 남기므로, 그보다 길고 눈에 띄는 값을 쓴다. */
    private static final String API_KEY = "chnx_live_SUPERSECRET_1a2b";

    private static final String ICAL_URL =
            "https://www.airbnb.com/calendar/ical/12345.ics?s=SECRETTOKEN";

    /** Channex 숙소 식별자. 비밀이 아니고 연결의 일부다. */
    private static final String CHANNEX_PROPERTY = "17e754e7-9aa8-456a-ad0a-94e1d54bc8f3";

    @Autowired
    private ChannelCredentialStore credentialStore;

    @Autowired
    private JdbcTemplate jdbc;

    // --- 자격 증명 (완료 조건 3·4·5·7) ----------------------------------------

    @Test
    @DisplayName("데이터베이스 행에 자격 증명 평문이 없다")
    void 저장된_행에_평문이_없다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long connectionId = 연결생성(세션, propertyId, "CHANNEX", "CHANNEX", API_KEY);

        String stored = jdbc.queryForObject(
                "SELECT credentials::text FROM channel_connection WHERE id = ?",
                String.class, connectionId);

        assertThat(stored)
                .as("암호문이라 원문이 나오면 안 된다")
                .doesNotContain(API_KEY)
                .doesNotContain("SUPERSECRET");
        // 키 이름은 비밀이 아니다. 값만 암호화한다.
        assertThat(stored).contains("api_key");
    }

    @Test
    @DisplayName("조회 응답 어디에도 자격 증명 평문이 없다")
    void 응답을_통째로_훑어도_평문이_없다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long connectionId = 연결생성(세션, propertyId, "AIRBNB_ICAL", "ICAL", ICAL_URL);

        // 자격 증명이 실릴 수 있는 경로 전부. 하나라도 빠뜨리면 그 경로로 샌다.
        List<String> 응답들 = List.of(
                본문(get("/api/channels"), 세션),
                본문(get("/api/channels/" + connectionId), 세션),
                본문(get("/api/channels/" + connectionId + "/mappings"), 세션));

        for (String 응답 : 응답들) {
            assertThat(응답)
                    .doesNotContain(ICAL_URL)
                    .doesNotContain("SECRETTOKEN");
        }
        // 마스킹된 형태는 나온다. 호스트가 무엇을 넣었는지 확인할 수 있어야 한다.
        assertThat(응답들.get(0)).contains("••••");
    }

    @Test
    @DisplayName("자격 증명을 빈 값으로 수정하면 기존 값이 유지된다")
    void 빈_자격증명은_기존_값을_지우지_않는다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long connectionId = 연결생성(세션, propertyId, "CHANNEX", "CHANNEX", API_KEY);

        // 화면은 저장된 자격 증명을 다시 받지 못하므로(마스킹된 형태만 본다), 표시 이름만
        // 고치려는 수정에서 자격 증명 칸이 비어 온다. 그걸 반영하면 연결이 조용히 끊긴다.
        mvc.perform(patch("/api/channels/" + connectionId)
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"이름만 고침","credentials":{"api_key":""}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("이름만 고침"));

        String 저장된값 = jdbc.queryForObject(
                "SELECT credentials::text FROM channel_connection WHERE id = ?",
                String.class, connectionId);
        assertThat(credentialStore.reveal(저장된값)).containsEntry("api_key", API_KEY);
    }

    @Test
    @DisplayName("연결 생성·수정·삭제가 감사에 남고 전후 값에 자격 증명이 없다")
    void 감사_기록에_자격증명이_없다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long connectionId = 연결생성(세션, propertyId, "CHANNEX", "CHANNEX", API_KEY);

        mvc.perform(patch("/api/channels/" + connectionId)
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"credentials":{"api_key":"chnx_live_ROTATED_9z8y"}}
                                """))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/channels/" + connectionId)
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("""
                SELECT action FROM audit_log
                 WHERE entity_type = 'CHANNEL_CONNECTION' AND entity_id = ? ORDER BY id
                """, String.class, connectionId))
                .containsExactly("CHANNEL_CONNECT", "CHANNEL_UPDATE", "CHANNEL_DISCONNECT");

        // audit_log 의 전후 값은 평문 JSONB 다. 여기에 자격 증명이 실리면 암호화를
        // 우회하는 두 번째 경로가 된다(ADR 0007 결과 절).
        String 기록전체 = String.join("\n", jdbc.queryForList("""
                SELECT coalesce(before_value::text, '') || coalesce(after_value::text, '')
                  FROM audit_log WHERE entity_type = 'CHANNEL_CONNECTION' AND entity_id = ?
                """, String.class, connectionId));

        assertThat(기록전체)
                .doesNotContain(API_KEY)
                .doesNotContain("SUPERSECRET")
                .doesNotContain("ROTATED");
        assertThat(기록전체)
                .as("무엇이 바뀌었는지는 남아야 한다")
                .contains("변경됨");
    }

    // --- 조직 스코핑 (완료 조건 6) --------------------------------------------

    @Test
    void 남의_숙소에는_연결을_만들_수_없다() throws Exception {
        Session 주인 = 가입(새이메일());
        Long propertyId = 숙소등록(주인);
        Session 남 = 가입(새이메일());

        mvc.perform(post("/api/properties/" + propertyId + "/channels")
                        .header(HttpHeaders.AUTHORIZATION, 남.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelCode":"CHANNEX","adapterType":"CHANNEX",
                                 "credentials":{"api_key":"%s"}}
                                """.formatted(API_KEY)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("남의 연결은 조회·수정·삭제 모두 404 다")
    void 남의_연결은_보이지_않는다() throws Exception {
        Session 주인 = 가입(새이메일());
        Long connectionId = 연결생성(주인, 숙소등록(주인), "CHANNEX", "CHANNEX", API_KEY);
        Session 남 = 가입(새이메일());

        mvc.perform(get("/api/channels/" + connectionId)
                        .header(HttpHeaders.AUTHORIZATION, 남.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/channels/" + connectionId)
                        .header(HttpHeaders.AUTHORIZATION, 남.bearer()))
                .andExpect(status().isNotFound());
        // 목록에도 섞이지 않는다
        mvc.perform(get("/api/channels").header(HttpHeaders.AUTHORIZATION, 남.bearer()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- 매핑 (완료 조건 8·9) -------------------------------------------------

    @Test
    @DisplayName("같은 연결 안에서 한 판매 단위를 두 번 매핑하면 거절된다")
    void 중복_매핑은_거절된다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long unitId = 판매단위등록(세션, propertyId, "본채");
        Long connectionId = 연결생성(세션, propertyId, "CHANNEX", "CHANNEX", API_KEY);

        매핑생성(세션, connectionId, unitId, "room_type_1").andExpect(status().isCreated());

        // 채널 쪽 식별자가 달라도 우리 재고는 같다. 통과시키면 같은 재고를 두 번 보낸다.
        매핑생성(세션, connectionId, unitId, "room_type_2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CHANNEL_MAPPING"));
    }

    @Test
    @DisplayName("한 판매 단위가 채널 여럿에 매핑되는 것은 막지 않는다")
    void 다른_연결에는_같은_단위를_매핑할_수_있다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long unitId = 판매단위등록(세션, propertyId, "본채");

        // 이게 이 제품의 존재 이유다. 막으면 안 된다. (iCal + Channex 만 예외다 — 아래 D.)
        매핑생성(세션, 연결생성(세션, propertyId, "BOOKING_COM", "CHANNEX", API_KEY), unitId, "rt_1")
                .andExpect(status().isCreated());
        매핑생성(세션, 연결생성(세션, propertyId, "MOCK_OTA", "MOCK", API_KEY), unitId, "room_1")
                .andExpect(status().isCreated());
    }

    // --- 작업지시-17 A·D (완료 조건 1·2) ---------------------------------------

    @Test
    @DisplayName("같은 판매 단위에 iCal 과 Channex 를 함께 매핑하면 어느 순서든 409 다")
    void iCal_과_Channex_는_한_판매_단위에_함께_매핑되지_않는다() throws Exception {
        // 에어비앤비가 Channex 를 거치면 같은 예약이 iCal 발행물로도 온다. 채널 코드가
        // 달라 uq_channel_booking 이 못 막고 초과 판매 충돌로 뜬다(조사-04 5절 3번).
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long 먼저iCal = 판매단위등록(세션, propertyId, "iCal 먼저");
        Long 먼저Channex = 판매단위등록(세션, propertyId, "Channex 먼저");
        Long ical = 연결생성(세션, propertyId, "AIRBNB_ICAL", "ICAL", ICAL_URL);
        Long channex = 연결생성(세션, propertyId, "BOOKING_COM", "CHANNEX", API_KEY);

        매핑생성(세션, ical, 먼저iCal, "listing_a").andExpect(status().isCreated());
        매핑생성(세션, channex, 먼저iCal, "rt_a")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAPPING_DOUBLE_INTAKE"));

        매핑생성(세션, channex, 먼저Channex, "rt_b").andExpect(status().isCreated());
        매핑생성(세션, ical, 먼저Channex, "listing_b")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAPPING_DOUBLE_INTAKE"));

        // 거절된 쪽은 남지 않았다 — 판매 단위마다 매핑이 하나뿐이다.
        Long 매핑수 = jdbc.queryForObject(
                "SELECT count(*) FROM channel_mapping WHERE unit_id IN (?, ?)", Long.class, 먼저iCal, 먼저Channex);
        assertThat(매핑수).isEqualTo(2);
    }

    @Test
    @DisplayName("Channex 연결은 api_key 와 property_id 둘 다 있어야 만들어지고, 매핑은 요금제 식별자가 있어야 한다")
    void Channex_는_키_둘과_요금제_식별자를_요구한다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long unitId = 판매단위등록(세션, propertyId, "본채");

        // property_id 없이 — 연결이 만들어지면 첫 전송에서야 IllegalStateException 으로 죽고 그건 워커 로그뿐이다.
        mvc.perform(post("/api/properties/" + propertyId + "/channels")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelCode":"BOOKING_COM","adapterType":"CHANNEX","credentials":{"api_key":"%s"}}
                                """.formatted(API_KEY)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANNEL_FIELD_MISSING"));

        Long channex = 연결생성(세션, propertyId, "BOOKING_COM", "CHANNEX", API_KEY);
        // 요금제 없이 방만 매핑하면 Channex 는 채널을 켜지 않는다(4절).
        매핑생성(세션, channex, unitId, "rt_1", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHANNEL_FIELD_MISSING"));
        매핑생성(세션, channex, unitId, "rt_1", "rp_1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalRateId").value("rp_1"));

        // 응답의 자격 증명 — 키는 가려지고 숙소 식별자도 같은 규칙으로 나간다(완료 조건 1).
        mvc.perform(get("/api/channels/" + channex).header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channelCode").value("BOOKING_COM"))
                .andExpect(jsonPath("$.credentials.api_key").value(org.hamcrest.Matchers.not(API_KEY)))
                .andExpect(jsonPath("$.credentials.api_key").value(org.hamcrest.Matchers.containsString("••••")))
                .andExpect(jsonPath("$.credentials.property_id").value(org.hamcrest.Matchers.containsString("••••")));
    }

    @Test
    @DisplayName("iCal 은 숙소 하나에 같은 채널 연결이 여럿일 수 있고, 다른 어댑터는 하나다")
    void iCal_연결은_숙소당_여럿이다() throws Exception {
        // V8. 업체 메종드서촌은 숙소 하나에 2층·3층 피드가 둘이다 — iCal 은 주소 하나가
        // 판매 단위 하나라 "채널은 숙소 단위로 붙는다"는 V1 의 가정이 성립하지 않는다.
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long 이층 = 판매단위등록(세션, propertyId, "2층");
        Long 삼층 = 판매단위등록(세션, propertyId, "3층");

        Long 첫째 = 연결생성(세션, propertyId, "AIRBNB_ICAL", "ICAL", ICAL_URL);
        Long 둘째 = 연결생성(세션, propertyId, "AIRBNB_ICAL", "ICAL", ICAL_URL + "?t=other");
        매핑생성(세션, 첫째, 이층, "listing_2f").andExpect(status().isCreated());
        매핑생성(세션, 둘째, 삼층, "listing_3f").andExpect(status().isCreated());

        // Channex 는 그대로 숙소당 하나다. 예외를 iCal 에만 열었는지 여기서 본다.
        연결생성(세션, propertyId, "CHANNEX", "CHANNEX", API_KEY);
        mvc.perform(post("/api/properties/" + propertyId + "/channels")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelCode":"CHANNEX","adapterType":"CHANNEX","displayName":"둘째",
                                 "credentials":{"api_key":"k2","property_id":"p2"}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CHANNEL_CONNECTION"));
    }

    @Test
    @DisplayName("매핑되지 않은 판매 단위가 목록에서 구분된다")
    void 매핑되지_않은_단위가_드러난다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long 매핑한단위 = 판매단위등록(세션, propertyId, "본채");
        판매단위등록(세션, propertyId, "별채");
        Long connectionId = 연결생성(세션, propertyId, "CHANNEX", "CHANNEX", API_KEY);
        매핑생성(세션, connectionId, 매핑한단위, "room_type_1");

        // 매핑되지 않은 단위는 그 채널에 나가지 않는다. 목록이 그걸 보여 줘야 한다.
        // 응답에서 mapping 이 없는 행이 매핑되지 않은 단위다(non_null 직렬화).
        mvc.perform(get("/api/channels/" + connectionId + "/mappings")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units.length()").value(2))
                .andExpect(jsonPath("$.units[0].unitName").value("본채"))
                .andExpect(jsonPath("$.units[0].mapping.externalUnitId").value("room_type_1"))
                .andExpect(jsonPath("$.units[1].unitName").value("별채"))
                .andExpect(jsonPath("$.units[1].mapping").doesNotExist());
    }

    @Test
    void 남의_판매_단위는_매핑할_수_없다() throws Exception {
        Session 주인 = 가입(새이메일());
        Long connectionId = 연결생성(주인, 숙소등록(주인), "CHANNEX", "CHANNEX", API_KEY);
        Session 남 = 가입(새이메일());
        Long 남의단위 = 판매단위등록(남, 숙소등록(남), "남의 방");

        매핑생성(주인, connectionId, 남의단위, "room_type_1")
                .andExpect(status().isNotFound());
    }

    // --- 기능 표시 (화면 완료 조건 11 의 서버 쪽) -------------------------------

    @Test
    @DisplayName("iCal 연결의 응답에 PUSH_RATE 가 없다")
    void iCal_은_요금_전파를_지원하지_않는다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션);
        Long ical = 연결생성(세션, propertyId, "AIRBNB_ICAL", "ICAL", ICAL_URL);
        // PUSH_RATE 의 양성 예는 Mock 이다. Channex 는 구현된 만큼만 선언한다(작업지시-17 E).
        Long channex = 연결생성(세션, propertyId, "MOCK_OTA", "MOCK", API_KEY);

        // 화면은 어댑터 종류의 선언을 보여 준다.
        mvc.perform(get("/api/channels/" + ical).header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(jsonPath("$.capabilities").value(org.hamcrest.Matchers.hasItem("PULL_BOOKING")))
                .andExpect(jsonPath("$.capabilities").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("PUSH_RATE"))));
        mvc.perform(get("/api/channels/" + channex).header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(jsonPath("$.capabilities").value(org.hamcrest.Matchers.hasItem("PUSH_RATE")));
    }

    // --- 픽스처 -------------------------------------------------------------

    private String 본문(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder 요청,
                      Session 세션) throws Exception {
        return mvc.perform(요청.header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Long 숙소등록(Session 세션) throws Exception {
        MvcResult result = mvc.perform(post("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"채널테스트 숙소"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long 판매단위등록(Session 세션, Long propertyId, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/properties/" + propertyId + "/units")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","unitKind":"ENTIRE_PLACE","totalUnits":1,
                                 "basePrice":100000}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long 연결생성(Session 세션, Long propertyId, String channelCode,
                      String adapterType, String secret) throws Exception {
        String key = "ICAL".equals(adapterType) ? "ical_url" : "api_key";
        // Channex 는 숙소 식별자도 있어야 만들어진다(작업지시-17 A). 비밀은 아니다.
        String extra = "CHANNEX".equals(adapterType) ? ",\"property_id\":\"" + CHANNEX_PROPERTY + "\"" : "";
        MvcResult result = mvc.perform(post("/api/properties/" + propertyId + "/channels")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelCode":"%s","adapterType":"%s","displayName":"%s",
                                 "credentials":{"%s":"%s"%s}}
                                """.formatted(channelCode, adapterType, channelCode, key, secret, extra)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions 매핑생성(
            Session 세션, Long connectionId, Long unitId, String externalUnitId) throws Exception {
        return 매핑생성(세션, connectionId, unitId, externalUnitId, "rp_1");
    }

    private org.springframework.test.web.servlet.ResultActions 매핑생성(
            Session 세션, Long connectionId, Long unitId, String externalUnitId,
            String externalRateId) throws Exception {
        String rate = externalRateId == null ? "" : ",\"externalRateId\":\"" + externalRateId + "\"";
        return mvc.perform(post("/api/channels/" + connectionId + "/mappings")
                .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"unitId":%d,"externalUnitId":"%s"%s}
                        """.formatted(unitId, externalUnitId, rate)));
    }

    private static String 새이메일() {
        return "channel-" + UUID.randomUUID() + "@example.com";
    }
}
