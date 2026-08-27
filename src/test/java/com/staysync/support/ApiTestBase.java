package com.staysync.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP 표면을 검증하는 테스트의 공통 설정.
 *
 * <p>포트와 데이터 디렉터리를 앱(15432)과도, {@code InventoryConcurrencyTest}(15433)와도
 * 분리한다. 앞은 앱을 띄운 채 테스트를 돌릴 때를 위한 것이고, 뒤는 이유가 다르다.
 *
 * <p>{@code @AutoConfigureMockMvc} 가 컨텍스트 캐시 키를 바꾼다. 그래서 이 계열 테스트는
 * {@code InventoryConcurrencyTest} 와 스프링 컨텍스트를 공유하지 못하고 두 번째 컨텍스트가
 * 만들어진다. 두 컨텍스트가 각자 내장 PostgreSQL 을 띄우므로 데이터 디렉터리가 같으면
 * 뒤에 뜨는 쪽의 {@code initdb} 가 실패한다. 포트와 디렉터리를 갈라야 하는 이유다.
 *
 * <p>가입 한 번으로 조직과 계정, 토큰 한 쌍이 모두 생기므로 별도 픽스처 SQL 이 필요 없다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15434",
        "staysync.embedded-postgres.data-directory=.localdb-api"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
public abstract class ApiTestBase {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    /** 가입해서 액세스 토큰과 리프레시 쿠키를 받는다. */
    protected Session 가입(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"충분히긴비밀번호1234",
                                 "displayName":"테스트","orgName":"테스트 조직"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return sessionFrom(result);
    }

    protected MvcResult 로그인시도(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andReturn();
    }

    protected Session sessionFrom(MvcResult result) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        Cookie cookie = result.getResponse().getCookie("refresh_token");
        return new Session(
                body.get("accessToken").asText(),
                cookie == null ? null : cookie.getValue());
    }

    /** 인증된 클라이언트가 들고 다니는 것. */
    protected record Session(String accessToken, String refreshToken) {

        public String bearer() {
            return "Bearer " + accessToken;
        }

        public Cookie refreshCookie() {
            return new Cookie("refresh_token", refreshToken);
        }
    }
}
