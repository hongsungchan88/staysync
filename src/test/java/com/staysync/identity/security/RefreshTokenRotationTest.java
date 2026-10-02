package com.staysync.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.staysync.support.ApiTestBase;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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

    @Autowired
    private RefreshTokenService refreshTokens;

    @Autowired
    private com.staysync.identity.RefreshTokenRepository repository;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    // --- 공용 계정 동시 사용 — 확인-12 5.1 의 재현 표(작업지시-21 8.5, ADR 0005 결과 절) -----------

    private static final String 비밀번호 = "충분히긴비밀번호1234";

    @Test
    @DisplayName("같은 브라우저의 탭 둘이 30ms 어긋나 같은 옛 토큰으로 갱신해도 둘 다 통과하고 다른 세션도 산다")
    void 탭_둘이_어긋나_갱신해도_통과한다() throws Exception {
        String email = 새이메일();
        Session 심사자A = 가입(email);
        Session 심사자B = sessionFrom(로그인시도(email, 비밀번호));

        Session 첫탭 = 갱신(심사자A.refreshToken(), 200);
        Thread.sleep(30);
        Session 둘째탭 = 갱신(심사자A.refreshToken(), 200);

        assertThat(둘째탭.refreshToken()).isNotEqualTo(첫탭.refreshToken());
        // 브라우저 쿠키에는 늦게 온 쪽 하나가 남는다. 어느 쪽이 남아도 이어서 갱신된다.
        갱신(첫탭.refreshToken(), 200);
        갱신(둘째탭.refreshToken(), 200);
        갱신(심사자B.refreshToken(), 200);
    }

    @Test
    @DisplayName("갱신 응답을 잃고 옛 토큰으로 다시 보내도 통과하고 다른 세션도 산다")
    void 응답을_잃고_재시도해도_통과한다() throws Exception {
        String email = 새이메일();
        Session 심사자A = 가입(email);
        Session 심사자B = sessionFrom(로그인시도(email, 비밀번호));

        갱신(심사자A.refreshToken(), 200);           // 응답이 클라이언트에 닿지 못했다
        Session 재시도 = 갱신(심사자A.refreshToken(), 200);

        갱신(재시도.refreshToken(), 200);
        갱신(심사자B.refreshToken(), 200);
    }

    @Test
    @DisplayName("같은 순간의 두 갱신 — 회전 표시는 하나만 이기고, 진 쪽 토큰도 같은 묶음이라 탐지가 우회되지 않는다")
    void 같은_순간의_두_갱신은_하나만_이긴다() throws Exception {
        Session 심사자A = 가입(새이메일());
        String 원래해시 = RefreshTokenService.sha256Hex(심사자A.refreshToken());

        java.util.concurrent.CyclicBarrier 출발 = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Callable<String> 갱신하기 = () -> {
            출발.await();
            return refreshTokens.rotate(심사자A.refreshToken(), OffsetDateTime.now()).rawToken();
        };
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var 첫째 = pool.submit(갱신하기);
        var 둘째 = pool.submit(갱신하기);
        String 토큰1 = 첫째.get();
        String 토큰2 = 둘째.get();
        pool.shutdown();

        Map<String, Object> 원래 = jdbc.queryForMap(
                "SELECT replaced_by, family_id FROM refresh_token WHERE token_hash = ?", 원래해시);
        Long 이긴쪽 = ((Number) 원래.get("replaced_by")).longValue();
        List<Map<String, Object>> 새토큰들 = jdbc.queryForList(
                "SELECT id, family_id FROM refresh_token WHERE token_hash IN (?, ?)",
                RefreshTokenService.sha256Hex(토큰1), RefreshTokenService.sha256Hex(토큰2));
        assertThat(새토큰들).hasSize(2);
        assertThat(새토큰들).filteredOn(t -> 이긴쪽.equals(((Number) t.get("id")).longValue()))
                .as("회전 표시는 하나만 — 원래 토큰은 한 번만 교체된다").hasSize(1);
        assertThat(새토큰들).allSatisfy(t -> assertThat(t.get("family_id")).isEqualTo(원래.get("family_id")));

        // 유예가 지난 뒤 원래 토큰이 다시 오면 두 갈래가 함께 끊긴다 — 경합이 탐지를 피하는 길이 아니다.
        유예를_넘긴다(원래해시);
        갱신(심사자A.refreshToken(), 401);
        갱신(토큰1, 401);
        갱신(토큰2, 401);
    }

    @Test
    @DisplayName("유예 밖의 진짜 재사용 — 그 로그인의 체인만 끊기고 같은 계정의 다른 세션은 산다")
    void 유예_밖의_재사용은_그_체인만_끊는다() throws Exception {
        String email = 새이메일();
        Session 심사자A = 가입(email);
        Session 심사자B = sessionFrom(로그인시도(email, 비밀번호));
        Long userId = userIdOf(심사자A);

        Session 새토큰 = 갱신(심사자A.refreshToken(), 200);
        유예를_넘긴다(RefreshTokenService.sha256Hex(심사자A.refreshToken()));

        // 이미 교체된 토큰이 유예 밖에서 다시 온다 — 유출된 토큰이 쓰이는 상황
        갱신(심사자A.refreshToken(), 401);

        갱신(새토큰.refreshToken(), 401);   // 같은 체인이라 함께 끊긴다
        갱신(심사자B.refreshToken(), 200);  // 다른 로그인은 산다
        assertThat(살아있는토큰수(userId)).as("심사자 B 의 갱신 결과 하나").isEqualTo(1);
    }

    @Test
    @DisplayName("로그아웃은 그 로그인의 체인을 끊는다 — 유예 안의 옛 토큰으로 되살아나지 않고, 다른 세션은 산다")
    void 로그아웃하면_유예_안의_옛_토큰으로도_못_돌아온다() throws Exception {
        String email = 새이메일();
        Session 심사자A = 가입(email);
        Session 심사자B = sessionFrom(로그인시도(email, 비밀번호));
        Session 새토큰 = 갱신(심사자A.refreshToken(), 200);

        mvc.perform(post("/api/auth/logout")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, 새토큰.bearer())
                        .cookie(새토큰.refreshCookie()))
                .andExpect(status().isOk());

        갱신(심사자A.refreshToken(), 401);
        갱신(심사자B.refreshToken(), 200);
    }

    @Test
    @DisplayName("조건부 회전 — 같은 토큰에 두 번째 회전 표시는 0 행이다")
    void 조건부_회전은_한_번만_된다() throws Exception {
        Session 세션 = 가입(새이메일());
        Long id = jdbc.queryForObject("SELECT id FROM refresh_token WHERE token_hash = ?", Long.class,
                RefreshTokenService.sha256Hex(세션.refreshToken()));
        int 첫째 = new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(t -> repository.markRotated(id, id, OffsetDateTime.now()));
        int 둘째 = new org.springframework.transaction.support.TransactionTemplate(txManager)
                .execute(t -> repository.markRotated(id, id, OffsetDateTime.now()));
        assertThat(첫째).isEqualTo(1);
        assertThat(둘째).isZero();
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
                INSERT INTO refresh_token (user_id, token_hash, expires_at, family_id)
                VALUES (?, ?, ?, ?)
                """, userId, RefreshTokenService.sha256Hex(UUID.randomUUID().toString()), expiresAt,
                UUID.randomUUID().toString());
    }

    /** 갱신 한 번. 기대한 상태가 아니면 실패한다. 200 이면 새 세션을 돌려준다. */
    private Session 갱신(String rawRefreshToken, int 기대상태) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", rawRefreshToken)))
                .andExpect(status().is(기대상태))
                .andReturn();
        return 기대상태 == 200 ? sessionFrom(result) : null;
    }

    /** 그 토큰이 유예보다 오래전에 교체된 것으로 만든다. 시계를 기다리지 않는다. */
    private void 유예를_넘긴다(String tokenHash) {
        jdbc.update("UPDATE refresh_token SET rotated_at = ? WHERE token_hash = ?",
                OffsetDateTime.now().minus(RefreshTokenService.REUSE_GRACE).minusSeconds(1), tokenHash);
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
