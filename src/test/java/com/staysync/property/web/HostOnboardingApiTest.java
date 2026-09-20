package com.staysync.property.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.staysync.support.ApiTestBase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 작업지시-19. 호스트가 혼자 시작하는 흐름 — 가입, 빈 상태, 숙소·판매 단위 등록·편집, 수량 변경.
 * 완료 조건 1·2·3·4·5·6·7.
 *
 * <p>전부 HTTP 로 부른다. 수량 변경(C)은 원장 행을 SQL 로 직접 본다 — 화면 값만으로는
 * 원장이 따라갔는지 모른다(작업지시-18 9.4.1 이 그렇게 숨어 있었다).
 */
class HostOnboardingApiTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    // --- 완료 조건 1 ----------------------------------------------------------

    @Test
    @DisplayName("가입하면 로그인 상태가 되고 캘린더(숙소 목록)까지 이어진다. 같은 이메일은 409 와 메시지")
    void 가입에서_캘린더까지() throws Exception {
        String email = 새이메일();
        MvcResult signup = mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"충분히긴비밀번호1234","displayName":"호스트","orgName":"새 조직"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();
        Session s = sessionFrom(signup);
        assertThat(s.refreshToken()).as("로그인과 같은 상태 — 리프레시 쿠키까지 온다").isNotNull();

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, s.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.displayName").value("호스트"));
        // 캘린더 화면이 처음 부르는 것. 숙소가 없으니 빈 목록이고 오류가 아니다.
        mvc.perform(get("/api/properties").header(HttpHeaders.AUTHORIZATION, s.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"충분히긴비밀번호1234","displayName":"둘째","orgName":"다른 조직"}
                                """.formatted(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("이미 사용 중인 이메일입니다.")));
    }

    // --- 완료 조건 2 ----------------------------------------------------------

    @Test
    @DisplayName("가입 직후 숙소 0 개에서 화면 다섯이 부르는 API 가 전부 오류 없이 빈 상태다")
    void 숙소가_없어도_화면_다섯이_비어_있을_뿐_오류가_아니다() throws Exception {
        Session s = 가입(새이메일());
        String bearer = s.bearer();

        mvc.perform(get("/api/properties").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        // 리포트 — 숙소가 없으면 지표 여섯이 0 이다. 500 이나 400 이면 안 된다.
        mvc.perform(get("/api/reports").header(HttpHeaders.AUTHORIZATION, bearer)
                        .param("from", "2027-08-01").param("to", "2027-08-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldNights").value(0))
                .andExpect(jsonPath("$.availableNights").value(0))
                .andExpect(jsonPath("$.channelMix.length()").value(0));
        mvc.perform(get("/api/channels").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/conflicts").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/ops/tasks").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/inbox").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    // --- 완료 조건 3 ----------------------------------------------------------

    @Test
    @DisplayName("숙소를 등록하고 이름·주소·체크인/체크아웃을 고친다. 다른 조직의 숙소는 못 고친다")
    void 숙소_등록과_편집() throws Exception {
        Session 갑 = 가입(새이메일());
        Session 을 = 가입(새이메일());
        Long propertyId = 숙소등록(갑, "처음 이름");

        mvc.perform(patch("/api/properties/" + propertyId)
                        .header(HttpHeaders.AUTHORIZATION, 갑.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"바뀐 이름","address":"서울 마포구","checkInTime":"16:00","checkOutTime":"10:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("바뀐 이름"))
                .andExpect(jsonPath("$.address").value("서울 마포구"))
                .andExpect(jsonPath("$.checkInTime").value("16:00:00"))
                .andExpect(jsonPath("$.checkOutTime").value("10:00:00"));

        // 시각은 둘이 함께여야 바뀐다. 하나만 보내면 무시된다 — 체크인이 체크아웃보다 늦는 조합을 막는다.
        mvc.perform(patch("/api/properties/" + propertyId)
                        .header(HttpHeaders.AUTHORIZATION, 갑.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"checkInTime":"18:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkInTime").value("16:00:00"));

        mvc.perform(patch("/api/properties/" + propertyId)
                        .header(HttpHeaders.AUTHORIZATION, 을.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"가로채기"}
                                """))
                .andExpect(status().isNotFound());
    }

    // --- 완료 조건 4 ----------------------------------------------------------

    @Test
    @DisplayName("판매 단위를 등록하면 기본 요금제가 함께 생기고, 이름만 바꾸는 PATCH 는 수량을 받지 않는다")
    void 판매_단위_등록() throws Exception {
        Session s = 가입(새이메일());
        Long propertyId = 숙소등록(s, "등록 숙소");
        Long unitId = 판매단위등록(s, propertyId, "본채", (short) 2);

        mvc.perform(get("/api/units/" + unitId + "/rate-plans")
                        .header(HttpHeaders.AUTHORIZATION, s.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].isDefault").value(true));

        // 이름은 property 의 PATCH. 수량은 여기서 받지 않는다 — 원장이 따라가야 해서 /capacity 로 간다.
        mvc.perform(patch("/api/units/" + unitId)
                        .header(HttpHeaders.AUTHORIZATION, s.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"별채","totalUnits":9}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("별채"))
                .andExpect(jsonPath("$.totalUnits").value(2));
    }

    // --- 완료 조건 5·6·7 ------------------------------------------------------

    @Test
    @DisplayName("수량을 늘리면 오늘 이후 원장 행이 따라가고 초과분이 다시 계산된다")
    void 수량을_늘리면_원장이_따라간다() throws Exception {
        Session s = 가입(새이메일());
        Long propertyId = 숙소등록(s, "수량 숙소");
        Long unitId = 판매단위등록(s, propertyId, "도미토리", (short) 1);
        LocalDate 체크인 = LocalDate.now().plusDays(30);
        예약등록(s, propertyId, unitId, 체크인, 체크인.plusDays(2));   // 원장 2행: total 1, booked 1

        mvc.perform(patch("/api/units/" + unitId + "/capacity")
                        .header(HttpHeaders.AUTHORIZATION, s.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"totalUnits":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUnits").value(3));

        assertThat(원장(unitId)).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("total")).as("원장 행이 새 수량을 따라간다").isEqualTo(3);
            assertThat(row.get("booked")).isEqualTo(1);
            assertThat(row.get("overbooked")).isEqualTo(0);
        });
        // 캘린더의 가용도 새 수량을 본다. 원장이 안 따라가면 화면은 그대로 0 이다.
        mvc.perform(get("/api/properties/" + propertyId + "/calendar")
                        .header(HttpHeaders.AUTHORIZATION, s.bearer())
                        .param("from", 체크인.toString()).param("to", 체크인.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units[0].totalUnits").value(3))
                .andExpect(jsonPath("$.units[0].days[0].avail").value(2));
    }

    @Test
    @DisplayName("이미 팔린 날 아래로 줄이면 409 이고 막는 날짜가 details 에 담긴다. 아무것도 바뀌지 않는다")
    void 팔린_날_아래로는_못_줄인다() throws Exception {
        Session s = 가입(새이메일());
        Long propertyId = 숙소등록(s, "줄이기 숙소");
        Long unitId = 판매단위등록(s, propertyId, "도미토리", (short) 3);
        LocalDate 체크인 = LocalDate.now().plusDays(30);
        예약등록(s, propertyId, unitId, 체크인, 체크인.plusDays(1));
        예약등록(s, propertyId, unitId, 체크인, 체크인.plusDays(2));   // 첫날 2, 둘째 날 1

        mvc.perform(patch("/api/units/" + unitId + "/capacity")
                        .header(HttpHeaders.AUTHORIZATION, s.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"totalUnits":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_BELOW_BOOKINGS"))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0]").value(체크인.toString()));

        // 강행해 초과 상태로 만들지 않는다. 판매 단위도 원장도 그대로다.
        mvc.perform(get("/api/properties/" + propertyId + "/units")
                        .header(HttpHeaders.AUTHORIZATION, s.bearer()))
                .andExpect(jsonPath("$[0].totalUnits").value(3));
        assertThat(원장(unitId)).allSatisfy(row -> {
            assertThat(row.get("total")).isEqualTo(3);
            assertThat(row.get("overbooked")).isEqualTo(0);
        });
    }

    @Test
    @DisplayName("팔린 수량 이상으로 줄이면 원장이 따라가고 가용이 맞다. 지난 날짜의 행은 그대로다")
    void 줄이기가_성공하면_원장이_따라간다() throws Exception {
        Session s = 가입(새이메일());
        Long propertyId = 숙소등록(s, "줄이기 성공 숙소");
        Long unitId = 판매단위등록(s, propertyId, "도미토리", (short) 3);
        LocalDate 체크인 = LocalDate.now().plusDays(30);
        예약등록(s, propertyId, unitId, 체크인, 체크인.plusDays(1));
        // 지난 날짜의 원장 행. 그날 팔 수 있었던 수량의 기록이라 건드리지 않는다.
        LocalDate 지난날 = LocalDate.now().minusDays(10);
        jdbc.update("INSERT INTO inventory_ledger (unit_id, stay_date, total_units, booked_units) VALUES (?, ?, 3, 3)",
                unitId, 지난날);

        mvc.perform(patch("/api/units/" + unitId + "/capacity")
                        .header(HttpHeaders.AUTHORIZATION, s.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"totalUnits":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUnits").value(2));

        Map<String, Object> 미래 = 원장(unitId, 체크인);
        assertThat(미래.get("total")).isEqualTo(2);
        assertThat(미래.get("booked")).isEqualTo(1);
        Map<String, Object> 과거 = 원장(unitId, 지난날);
        assertThat(과거.get("total")).as("지난 행은 그대로").isEqualTo(3);

        mvc.perform(get("/api/properties/" + propertyId + "/calendar")
                        .header(HttpHeaders.AUTHORIZATION, s.bearer())
                        .param("from", 체크인.toString()).param("to", 체크인.plusDays(1).toString()))
                .andExpect(jsonPath("$.units[0].days[0].avail").value(1))
                // 원장 행이 없는 날은 판매 단위 수량 그대로다.
                .andExpect(jsonPath("$.units[0].days[1].avail").value(2));
    }

    // --- 픽스처 ---------------------------------------------------------------

    private Long 숙소등록(Session s, String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, s.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long 판매단위등록(Session s, Long propertyId, String name, short totalUnits) throws Exception {
        MvcResult r = mvc.perform(post("/api/properties/" + propertyId + "/units")
                        .header(HttpHeaders.AUTHORIZATION, s.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","unitKind":"SHARED_ROOM","totalUnits":%d,"basePrice":50000}
                                """.formatted(name, totalUnits)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private void 예약등록(Session s, Long propertyId, Long unitId, LocalDate checkIn, LocalDate checkOut)
            throws Exception {
        mvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, s.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyId":%d,"unitId":%d,"checkIn":"%s","checkOut":"%s",
                                 "totalAmount":100000,"adults":1,"children":0,"guestName":"손님"}
                                """.formatted(propertyId, unitId, checkIn, checkOut)))
                .andExpect(status().isCreated());
    }

    private List<Map<String, Object>> 원장(Long unitId) {
        return jdbc.query(
                "SELECT total_units, booked_units, overbooked_units FROM inventory_ledger WHERE unit_id = ? ORDER BY stay_date",
                (rs, i) -> Map.of("total", rs.getInt(1), "booked", rs.getInt(2), "overbooked", rs.getInt(3)),
                unitId);
    }

    private Map<String, Object> 원장(Long unitId, LocalDate date) {
        return jdbc.queryForObject(
                "SELECT total_units, booked_units, overbooked_units FROM inventory_ledger WHERE unit_id = ? AND stay_date = ?",
                (rs, i) -> Map.of("total", rs.getInt(1), "booked", rs.getInt(2), "overbooked", rs.getInt(3)),
                unitId, date);
    }

    private static String 새이메일() {
        return "host-" + UUID.randomUUID() + "@example.com";
    }
}
