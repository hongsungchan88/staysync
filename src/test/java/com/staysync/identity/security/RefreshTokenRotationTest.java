package com.staysync.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.staysync.support.ApiTestBase;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** 완료 조건 2. 회전과 재사용 탐지. */
class RefreshTokenRotationTest extends ApiTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RefreshTokenCleanupJob cleanupJob;

    @Test
    @DisplayName("회전된 리프레시 토큰이 다시 오면 그 사용자의 토큰이 전부 무효화된다")
    void 교체된_리프레시_토큰이_다시_오면_전부_무효화된다() throws Exception {
        Session 최초 = 가입(새이메일());
        Long userId = userIdOf(최초);

        // 한 번 갱신해 최초 토큰을 회전시킨다
        MvcResult 갱신결과 = mvc.perform(post("/api/auth/refresh").cookie(최초.refreshCookie()))
                .andExpect(status().isOk())
                .andReturn();
        Session 새토큰 = sessionFrom(갱신결과);

        assertThat(살아있는토큰수(userId))
                .as("회전 직후에는 새 토큰 하나만 살아 있다")
                .isEqualTo(1);

        // 이미 교체된 토큰을 다시 제시한다 — 유출된 토큰이 쓰이는 상황
        mvc.perform(post("/api/auth/refresh").cookie(최초.refreshCookie()))
                .andExpect(status().isUnauthorized());

        assertThat(살아있는토큰수(userId))
                .as("도난으로 보고 그 사용자의 토큰을 전부 끊어야 한다")
                .isZero();

        // 정상적으로 회전됐던 토큰까지 함께 무효화되어 더는 갱신할 수 없다
        mvc.perform(post("/api/auth/refresh").cookie(새토큰.refreshCookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 갱신하면_리프레시_토큰이_회전한다() throws Exception {
        Session 최초 = 가입(새이메일());

        MvcResult 결과 = mvc.perform(post("/api/auth/refresh").cookie(최초.refreshCookie()))
                .andExpect(status().isOk())
                .andReturn();

        Session 갱신 = sessionFrom(결과);
        assertThat(갱신.refreshToken()).isNotEqualTo(최초.refreshToken());
        assertThat(갱신.accessToken()).isNotBlank();
    }

    @Test
    void 존재하지_않는_리프레시_토큰은_거절된다() throws Exception {
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "없는토큰")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 쿠키가_없으면_갱신되지_않는다() throws Exception {
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("정리 배치는 유예 기간이 지난 만료 토큰만 지운다")
    void 유예_기간이_지난_만료_토큰만_정리된다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long userId = userIdOf(세션);

        // 만료됐지만 유예(7일) 안에 있는 토큰. 로그에서 "만료"와 "없음"을 가르려면 남아야 한다.
        만료된토큰삽입(userId, OffsetDateTime.now().minusDays(1));
        // 유예를 넘긴 토큰
        만료된토큰삽입(userId, OffsetDateTime.now().minusDays(30));

        int 삭제 = cleanupJob.deleteExpired(OffsetDateTime.now());

        assertThat(삭제).isEqualTo(1);
        assertThat(전체토큰수(userId))
                .as("살아 있는 토큰 1건 + 유예 안의 만료 토큰 1건")
                .isEqualTo(2);
    }

    private void 만료된토큰삽입(Long userId, OffsetDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO refresh_token (user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """, userId, RefreshTokenService.sha256Hex(UUID.randomUUID().toString()), expiresAt);
    }

    private int 살아있는토큰수(Long userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM refresh_token
                 WHERE user_id = ? AND revoked_at IS NULL AND replaced_by IS NULL
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    private int 전체토큰수(Long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private Long userIdOf(Session session) throws Exception {
        MvcResult me = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/auth/me")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(me.getResponse().getContentAsString()).get("userId").asLong();
    }

    private static String 새이메일() {
        return "rotate-" + UUID.randomUUID() + "@example.com";
    }
}
