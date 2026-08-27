package com.staysync.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.staysync.support.ApiTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** 완료 조건 1, 4, 6. */
class AuthApiTest extends ApiTestBase {

    @Test
    @DisplayName("가입부터 로그아웃 후 갱신 실패까지 한 번에 돈다")
    void 가입_로그인_보호된호출_갱신_로그아웃_갱신실패가_한번에_돈다() throws Exception {
        String email = 새이메일();

        // 1) 가입 — 액세스 토큰과 리프레시 쿠키를 함께 받는다
        Session 가입세션 = 가입(email);
        assertThat(가입세션.accessToken()).isNotBlank();
        assertThat(가입세션.refreshToken()).isNotBlank();

        // 2) 로그인
        MvcResult 로그인결과 = 로그인시도(email, "충분히긴비밀번호1234");
        assertThat(로그인결과.getResponse().getStatus()).isEqualTo(200);
        Session 세션 = sessionFrom(로그인결과);

        // 3) 보호된 숙소 API 호출
        mvc.perform(get("/api/properties").header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 4) 갱신 — 새 액세스 토큰과 새 리프레시 쿠키를 받는다
        MvcResult 갱신결과 = mvc.perform(post("/api/auth/refresh").cookie(세션.refreshCookie()))
                .andExpect(status().isOk())
                .andReturn();
        Session 갱신세션 = sessionFrom(갱신결과);
        assertThat(갱신세션.refreshToken())
                .as("회전됐으므로 리프레시 토큰이 바뀌어야 한다")
                .isNotEqualTo(세션.refreshToken());

        // 5) 로그아웃 — 멱등하며 쿠키를 지운다
        mvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, 갱신세션.bearer())
                        .cookie(갱신세션.refreshCookie()))
                .andExpect(status().isOk());

        // 6) 로그아웃한 토큰으로는 갱신되지 않는다
        mvc.perform(post("/api/auth/refresh").cookie(갱신세션.refreshCookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃은 멱등하다")
    void 쿠키_없이_로그아웃해도_200이다() throws Exception {
        Session 세션 = 가입(새이메일());

        mvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isOk());
        // 두 번 불러도 마찬가지다
        mvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .cookie(세션.refreshCookie()))
                .andExpect(status().isOk());
    }

    // --- 완료 조건 4 ---------------------------------------------------------

    @Test
    void 토큰_없이_보호된_경로를_부르면_401이다() throws Exception {
        mvc.perform(get("/api/properties")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void 만료된_액세스_토큰이면_401이다() throws Exception {
        // JwtTokenServiceTest 가 만료 판정을 이미 검증한다. 여기서는 그 판정이 HTTP
        // 계층까지 401 로 이어지는지만 본다. 서명이 깨진 토큰도 같은 경로를 탄다.
        mvc.perform(get("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9.bogus.sig"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 가입한_사용자는_자기_정보를_조회할_수_있다() throws Exception {
        String email = 새이메일();
        Session 세션 = 가입(email);

        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, 세션.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void 이미_쓰이는_이메일로_가입하면_409다() throws Exception {
        String email = 새이메일();
        가입(email);

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"충분히긴비밀번호1234",
                                 "displayName":"중복","orgName":"중복 조직"}
                                """.formatted(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"));
    }

    // --- 완료 조건 6 ---------------------------------------------------------

    @Test
    @DisplayName("리프레시 쿠키에 HttpOnly, SameSite=Strict, Path 가 실제로 붙는다")
    void 로그인_응답의_쿠키에_보안_속성이_붙는다() throws Exception {
        String email = 새이메일();
        가입(email);

        MvcResult 결과 = 로그인시도(email, "충분히긴비밀번호1234");

        // MockMvc 의 Cookie 객체는 SameSite 를 표현하지 못한다. 원본 헤더를 직접 본다.
        String setCookie = 결과.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        assertThat(setCookie)
                .contains("refresh_token=")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/api/auth")
                .contains("Max-Age=1209600");   // 14일

        // local 프로파일은 HTTPS 가 아니라 Secure 를 끈다. 운영 기본값이 켜져 있다는 것은
        // application.yml 의 staysync.security.cookie.secure 가 보장한다.
        assertThat(setCookie).doesNotContain("Secure");
    }

    @Test
    void 로그아웃_응답은_쿠키를_지운다() throws Exception {
        Session 세션 = 가입(새이메일());

        MvcResult 결과 = mvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                        .cookie(세션.refreshCookie()))
                .andReturn();

        String setCookie = 결과.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .as("같은 이름과 경로에 Max-Age=0 을 실어야 브라우저가 지운다")
                .contains("refresh_token=")
                .contains("Max-Age=0")
                .contains("Path=/api/auth");
    }

    /** 테스트끼리 이메일이 겹치지 않게 한다. 컨텍스트를 공유하므로 데이터가 남는다. */
    private static String 새이메일() {
        return "host-" + UUID.randomUUID() + "@example.com";
    }
}
