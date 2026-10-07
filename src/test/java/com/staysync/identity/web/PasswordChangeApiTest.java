package com.staysync.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.staysync.support.ApiTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 비밀번호 변경(작업지시-23 2절 D, 완료 조건 7).
 *
 * <p>가장 중요한 것은 둘이다 — 현재 비밀번호 확인이 <b>로그인과 같은 시도 제한에 세어지는지</b>(안 그러면
 * 무차별 대입 통로), 바꾼 뒤 <b>다른 로그인 묶음만 끊기고 이 로그인은 사는지</b>(공용 계정의 다른 사람은
 * 다시 로그인한다).
 */
class PasswordChangeApiTest extends ApiTestBase {

    private static final String 옛비밀번호 = "충분히긴비밀번호1234";
    private static final String 새비밀번호 = "새로바꾼비밀번호5678";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("바꾸면 새 비밀번호로만 들어가고, 다른 로그인은 끊기고 바꾼 로그인은 계속 된다")
    void 바꾸면_다른_로그인만_끊긴다() throws Exception {
        String email = 새이메일();
        Session 나 = 가입(email);
        Session 다른사람 = sessionFrom(로그인시도(email, 옛비밀번호));

        변경(나, 옛비밀번호, 새비밀번호).andExpect(status().isNoContent());

        // 공용 계정의 다른 사람 — 그 묶음은 끊겼다.
        mvc.perform(post("/api/auth/refresh").cookie(다른사람.refreshCookie()))
                .andExpect(status().isUnauthorized());
        // 바꾼 사람 — 이 묶음은 남았다. 바꾸자마자 로그아웃되면 안 된다.
        mvc.perform(post("/api/auth/refresh").cookie(나.refreshCookie()))
                .andExpect(status().isOk());

        assertThat(로그인시도(email, 새비밀번호).getResponse().getStatus()).isEqualTo(200);
        assertThat(로그인시도(email, 옛비밀번호).getResponse().getStatus()).isEqualTo(401);

        List<String> 감사 = jdbc.queryForList("""
                SELECT a.after_value::text FROM audit_log a
                  JOIN user_account u ON u.id = a.entity_id
                 WHERE a.entity_type = 'USER_ACCOUNT' AND a.action = 'PASSWORD_CHANGED' AND u.email = ?
                """, String.class, email);
        assertThat(감사).hasSize(1);
        // 값도 해시도 남기지 않는다.
        assertThat(감사.get(0)).doesNotContain(옛비밀번호).doesNotContain(새비밀번호).doesNotContain("$2");
    }

    @Test
    @DisplayName("현재 비밀번호가 틀리면 400 이고, 로그인 실패와 같은 제한에 세어 다섯 번이면 잠긴다")
    void 틀린_현재_비밀번호는_시도_제한에_센다() throws Exception {
        String email = 새이메일();
        Session 나 = 가입(email);

        for (int i = 0; i < 5; i++) {
            // 401 이 아니다 — 401 이면 화면이 세션 만료로 읽고 로그아웃시킨다.
            변경(나, "틀린현재비밀번호000", 새비밀번호)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_MISMATCH"));
        }

        // 잠겼다. 맞는 비밀번호로도 바꿀 수 없고, 같은 계정의 로그인도 막힌다.
        변경(나, 옛비밀번호, 새비밀번호).andExpect(status().isTooManyRequests());
        assertThat(로그인시도(email, 옛비밀번호).getResponse().getStatus()).isEqualTo(429);
        assertThat(감사수(email)).isZero();
    }

    @Test
    @DisplayName("새 비밀번호가 가입 규칙(10~100자)에 안 맞으면 400 이고 바뀌지 않는다")
    void 규칙에_안_맞는_새_비밀번호는_거부한다() throws Exception {
        String email = 새이메일();
        Session 나 = 가입(email);

        변경(나, 옛비밀번호, "짧은비번123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("newPassword")));

        assertThat(로그인시도(email, 옛비밀번호).getResponse().getStatus()).isEqualTo(200);
        assertThat(감사수(email)).isZero();
    }

    @Test
    @DisplayName("로그인하지 않으면 401 이다")
    void 로그인하지_않으면_401() throws Exception {
        mvc.perform(post("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"" + 새비밀번호 + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions 변경(Session 세션, String 현재, String 새것) throws Exception {
        return mvc.perform(post("/api/auth/password")
                .header(HttpHeaders.AUTHORIZATION, 세션.bearer())
                .cookie(세션.refreshCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"%s","newPassword":"%s"}
                        """.formatted(현재, 새것)));
    }

    private int 감사수(String email) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_log a JOIN user_account u ON u.id = a.entity_id
                 WHERE a.entity_type = 'USER_ACCOUNT' AND a.action = 'PASSWORD_CHANGED' AND u.email = ?
                """, Integer.class, email);
    }

    private static String 새이메일() {
        return "pw-" + UUID.randomUUID() + "@example.com";
    }
}
