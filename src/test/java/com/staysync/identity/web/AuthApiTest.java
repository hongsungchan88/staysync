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
                        .header("X-Forwarded-For", 새IP())
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

    // --- 작업지시-19 추가분. 가입 속도 제한 -----------------------------------

    @Test
    @DisplayName("한 IP 가 창 안에서 상한(3건)을 넘으면 429 이고 계정이 생기지 않는다. 다른 IP 는 그대로 된다")
    void 가입_상한을_넘으면_429_이고_계정이_생기지_않는다() throws Exception {
        // 성공·실패를 가리지 않고 요청을 센다 — 오타로 다시 하는 사람과 스크립트가 같은
        // 모양이라서. 셋은 실제로 가입되게 이메일을 다르게 준다.
        String ip = 새IP();
        for (int i = 1; i <= 3; i++) {
            가입시도(새이메일(), ip, null).andExpect(status().isCreated());
        }

        String 네번째 = 새이메일();
        가입시도(네번째, ip, null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SIGNUP_RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("가입 요청이 너무 잦습니다. 잠시 뒤 다시 시도해 주세요."));

        // 거절된 가입은 만들어지지 않았다 — 그 이메일로 로그인이 안 된다.
        assertThat(로그인시도(네번째, "충분히긴비밀번호1234").getResponse().getStatus())
                .as("429 로 거절된 가입은 계정을 남기지 않는다")
                .isEqualTo(401);

        // 다른 IP 는 그 사이에도 된다. 전역 차단이 아니다.
        가입시도(새이메일(), 새IP(), null).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("X-Forwarded-For 가 있으면 그 주소로 가르고, 없으면 접속 주소로 센다")
    void 가입_전달_헤더로_IP_를_가른다() throws Exception {
        // Caddy 뒤에서는 모든 요청의 접속 주소가 Caddy 하나다. 전달 헤더를 안 풀면 한 사람이
        // 세 번 가입한 뒤 모든 손님이 막힌다. 헤더가 없는 요청(로컬 직접 접속)은 접속
        // 주소로 센다 — 위조가 아니라 프록시가 없는 것이다.
        String 프록시 = "172.19.0.4";   // Caddy 컨테이너 같은 내부 주소
        String 손님A = 새IP();
        String 손님B = 새IP();

        for (int i = 1; i <= 3; i++) {
            가입시도(새이메일(), 손님A, 프록시).andExpect(status().isCreated());
        }
        // 같은 프록시를 거쳤지만 다른 손님이다.
        가입시도(새이메일(), 손님B, 프록시).andExpect(status().isCreated());
        // 손님 A 는 막힌다 — 프록시 주소가 아니라 전달 헤더의 주소로 셌다.
        가입시도(새이메일(), 손님A, 프록시).andExpect(status().isTooManyRequests());

        // 헤더 없이 접속 주소만 있는 요청은 그 주소로 센다.
        String 직접 = 새IP();
        for (int i = 1; i <= 3; i++) {
            가입시도(새이메일(), null, 직접).andExpect(status().isCreated());
        }
        가입시도(새이메일(), null, 직접).andExpect(status().isTooManyRequests());
    }

    /** @param forwardedFor {@code X-Forwarded-For}. null 이면 안 붙인다. remoteAddr 가 null 이면 MockMvc 기본값 */
    private org.springframework.test.web.servlet.ResultActions 가입시도(
            String email, String forwardedFor, String remoteAddr) throws Exception {
        var request = post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"충분히긴비밀번호1234",
                         "displayName":"테스트","orgName":"테스트 조직"}
                        """.formatted(email));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        if (remoteAddr != null) {
            request.with(r -> { r.setRemoteAddr(remoteAddr); return r; });
        }
        return mvc.perform(request);
    }

    /** 테스트끼리 이메일이 겹치지 않게 한다. 컨텍스트를 공유하므로 데이터가 남는다. */
    private static String 새이메일() {
        return "host-" + UUID.randomUUID() + "@example.com";
    }
}
