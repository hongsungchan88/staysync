package com.staysync.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.staysync.channel.support.SyncTestBase;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V11 을 <b>이미 회전된 토큰 사슬이 있는 DB</b> 에 적용해 본다(V10MigrationTest 와 같은 방식).
 *
 * <p>운영 DB 에는 회전을 거친 사슬이 이미 있다. 뿌리마다 한 묶음으로 채우지 않으면 살아 있는 토큰과 그 앞의
 * 교체된 토큰이 다른 묶음이 되어, 배포 직후 옛 토큰의 재사용이 살아 있는 쪽을 끊지 못한다.
 */
class V11MigrationTest extends SyncTestBase {

    private static final String SCHEMA = "v11_check";

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("V10 상태의 회전 사슬에 V11 을 적용하면 사슬 하나가 묶음 하나, 다른 로그인은 다른 묶음이다")
    void 사슬마다_한_묶음으로_채운다() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        sql.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");

        flyway("10").migrate();

        Long orgId = sql.queryForObject(
                "INSERT INTO " + SCHEMA + ".organization (name) VALUES ('V11') RETURNING id", Long.class);
        Long userId = sql.queryForObject(
                "INSERT INTO " + SCHEMA + ".user_account (org_id, email, password_hash, display_name, role) "
                        + "VALUES (?, 'v11@example.com', 'x', 'V11', 'OWNER') RETURNING id", Long.class, orgId);
        // 로그인 하나에서 회전 두 번: 첫째 → 둘째 → 셋째(살아 있음). 뒤에서부터 넣어야 replaced_by 가 이어진다.
        Long 셋째 = 토큰(sql, userId, "c", null);
        Long 둘째 = 토큰(sql, userId, "b", 셋째);
        Long 첫째 = 토큰(sql, userId, "a", 둘째);
        // 같은 사용자의 다른 로그인(다른 기기)
        Long 다른로그인 = 토큰(sql, userId, "d", null);

        flyway("11").migrate();

        Map<Long, String> 묶음 = new java.util.HashMap<>();
        sql.query("SELECT id, family_id FROM " + SCHEMA + ".refresh_token",
                rs -> { 묶음.put(rs.getLong(1), rs.getString(2)); });
        assertThat(묶음.get(첫째)).isEqualTo("legacy-" + 첫째);
        assertThat(묶음.get(둘째)).isEqualTo(묶음.get(첫째));
        assertThat(묶음.get(셋째)).as("살아 있는 토큰이 옛 토큰과 같은 묶음이어야 재사용이 그것을 끊는다")
                .isEqualTo(묶음.get(첫째));
        assertThat(묶음.get(다른로그인)).isEqualTo("legacy-" + 다른로그인);
        assertThat(sql.queryForObject("SELECT count(*) FROM " + SCHEMA + ".refresh_token WHERE rotated_at IS NOT NULL",
                Integer.class)).as("기존 교체분은 유예 밖").isZero();

        assertThatThrownBy(() -> sql.update("INSERT INTO " + SCHEMA + ".refresh_token (user_id, token_hash, expires_at) "
                + "VALUES (?, 'e', now() + interval '1 day')", userId))
                .as("묶음 없는 토큰은 들어가지 않는다").isInstanceOf(DataIntegrityViolationException.class);

        sql.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    private static Long 토큰(JdbcTemplate sql, Long userId, String hash, Long replacedBy) {
        return sql.queryForObject("INSERT INTO " + SCHEMA + ".refresh_token (user_id, token_hash, expires_at, replaced_by) "
                + "VALUES (?, ?, now() + interval '14 days', ?) RETURNING id", Long.class, userId, hash, replacedBy);
    }

    /** 앱과 같은 파일, 같은 위치. 스키마만 갈라 실제 테스트 DB 를 건드리지 않는다. */
    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/postgresql")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .target(target)
                .load();
    }
}
