package com.staysync.property.web;

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

/** 완료 조건 3. 숙소·판매 단위·요금제 API 와 조직 스코핑. */
class PropertyApiTest extends ApiTestBase {

    // --- 기본 동작 -----------------------------------------------------------

    @Test
    void 숙소를_등록하면_목록에서_조회된다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션, "성수동 오피스텔");

        mvc.perform(get("/api/properties").header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(propertyId))
                .andExpect(jsonPath("$[0].name").value("성수동 오피스텔"));
    }

    @Test
    @DisplayName("목록은 평면이고 상세에만 units 가 실린다")
    void 목록에는_판매단위가_없고_상세에는_있다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션, "독채");
        판매단위등록(세션, propertyId, "본채", (short) 1);

        mvc.perform(get("/api/properties").header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(jsonPath("$[0].units").doesNotExist());

        mvc.perform(get("/api/properties/" + propertyId)
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.property.id").value(propertyId))
                .andExpect(jsonPath("$.units.length()").value(1))
                .andExpect(jsonPath("$.units[0].name").value("본채"));
    }

    @Test
    @DisplayName("판매 단위를 등록하면 기본 요금제가 함께 생성된다")
    void 판매_단위를_등록하면_기본_요금제가_함께_생성된다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션, "게스트하우스");
        Long unitId = 판매단위등록(세션, propertyId, "4인 도미토리", (short) 4);

        // 요금제 생성 엔드포인트는 열지 않았다. 그래도 조회하면 하나 있어야 한다.
        mvc.perform(get("/api/units/" + unitId + "/rate-plans")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[0].name").value("기본"));
    }

    @Test
    void 이름이_비어_있으면_400과_필드_오류를_반환한다() throws Exception {
        Session 세션 = 가입(새이메일());

        mvc.perform(post("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","address":"서울"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details").isNotEmpty());
    }

    @Test
    void 판매_수량이_0이면_400을_반환한다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션, "숙소");

        mvc.perform(post("/api/properties/" + propertyId + "/units")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"방","unitKind":"PRIVATE_ROOM","totalUnits":0,"basePrice":50000}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 숙소_이름을_수정할_수_있다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long propertyId = 숙소등록(세션, "옛 이름");

        mvc.perform(patch("/api/properties/" + propertyId)
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"새 이름"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 이름"));
    }

    // --- 완료 조건 3: 조직 스코핑 ---------------------------------------------

    @Test
    @DisplayName("다른 조직의 숙소는 조회되지 않는다")
    void 다른_조직의_숙소는_조회되지_않는다() throws Exception {
        Session 갑 = 가입(새이메일());
        Session 을 = 가입(새이메일());
        Long 갑의숙소 = 숙소등록(갑, "갑의 숙소");

        // 을의 목록에는 갑의 숙소가 없다
        mvc.perform(get("/api/properties").header(HttpHeaders.AUTHORIZATION, 을.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 식별자를 직접 넣어도 404 다. 403 이면 존재 여부가 새어 나간다.
        mvc.perform(get("/api/properties/" + 갑의숙소)
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROPERTY_NOT_FOUND"));

        // 수정도 막힌다
        mvc.perform(patch("/api/properties/" + 갑의숙소)
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"가로챈 이름"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("다른 조직의 판매 단위는 조회되지 않는다")
    void 다른_조직의_판매_단위는_조회되지_않는다() throws Exception {
        Session 갑 = 가입(새이메일());
        Session 을 = 가입(새이메일());
        Long 갑의숙소 = 숙소등록(갑, "갑의 숙소");
        Long 갑의판매단위 = 판매단위등록(갑, 갑의숙소, "갑의 방", (short) 1);

        // unit 에는 org_id 가 없다. property_id 를 거슬러 올라가야 막힌다.
        mvc.perform(get("/api/properties/" + 갑의숙소 + "/units")
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer()))
                .andExpect(status().isNotFound());

        mvc.perform(patch("/api/units/" + 갑의판매단위)
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"가로챈 방"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNIT_NOT_FOUND"));

        // 을의 숙소 아래에 갑의 판매 단위를 만들 수도 없다
        mvc.perform(post("/api/properties/" + 갑의숙소 + "/units")
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"끼워넣기","unitKind":"PRIVATE_ROOM","totalUnits":1,"basePrice":10000}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("다른 조직의 요금제는 조회되지 않는다")
    void 다른_조직의_요금제는_조회되지_않는다() throws Exception {
        Session 갑 = 가입(새이메일());
        Session 을 = 가입(새이메일());
        Long 갑의숙소 = 숙소등록(갑, "갑의 숙소");
        Long 갑의판매단위 = 판매단위등록(갑, 갑의숙소, "갑의 방", (short) 1);

        // rate_plan 에도 org_id 가 없다. unit → property 두 단계를 거슬러야 한다.
        mvc.perform(get("/api/units/" + 갑의판매단위 + "/rate-plans")
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer()))
                .andExpect(status().isNotFound());

        // 갑 본인은 볼 수 있다
        mvc.perform(get("/api/units/" + 갑의판매단위 + "/rate-plans")
                        .header(HttpHeaders.AUTHORIZATION, 갑.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // --- 헬퍼 ---------------------------------------------------------------

    private Long 숙소등록(Session session, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","address":"서울시 성동구"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long 판매단위등록(Session session, Long propertyId, String name, short totalUnits)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/properties/" + propertyId + "/units")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","unitKind":"PRIVATE_ROOM","totalUnits":%d,"basePrice":80000}
                                """.formatted(name, totalUnits)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private static String 새이메일() {
        return "prop-" + UUID.randomUUID() + "@example.com";
    }
}
