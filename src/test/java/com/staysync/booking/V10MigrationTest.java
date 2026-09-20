package com.staysync.booking;

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
 * V10 을 <b>부푼 행이 있는 DB</b> 에 실제로 적용해 본다(작업지시-18 9절, 사용자 지적 09-20).
 *
 * <p>첫 V10 은 UPDATE 를 옛 {@code chk_no_oversell} 을 내리기 전에 두었다. 부푼 행
 * ({@code booked + held} 가 판매 단위 수량보다 큰 행)의 {@code total} 을 내리는 순간 옛
 * 제약에 걸려 마이그레이션이 통째로 실패한다. 운영은 부푼 행이 0 이라 안 드러나고,
 * 테스트는 빈 DB 에서 시작해 못 잡았다. 그래서 여기서는 같은 DB 의 별도 스키마에
 * <b>V9 까지만</b> 올리고 부푼 행을 넣은 뒤 V10 을 적용한다 — 파일 그대로.
 */
class V10MigrationTest extends SyncTestBase {

    private static final String SCHEMA = "v10_check";

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("V9 상태의 부푼 행에 V10 을 적용하면 total 은 판매 단위 수량, overbooked 는 초과분이 된다")
    void 부푼_행이_있어도_V10_이_적용된다() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        sql.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");

        flyway("9").migrate();

        // V9 상태. 판매 단위 수량 1 인데 forceBook 이 그날의 total 을 2 로 올려 둔 행 —
        // 옛 제약(booked + held <= total)은 만족한다. 그리고 멀쩡한 행 하나.
        // 풀에서 꺼내는 커넥션이 호출마다 달라 SET search_path 가 붙지 않는다. 스키마를 앞에 붙인다.
        Long orgId = sql.queryForObject(
                "INSERT INTO " + SCHEMA + ".organization (name) VALUES ('V10') RETURNING id", Long.class);
        Long propertyId = sql.queryForObject(
                "INSERT INTO " + SCHEMA + ".property (org_id, name) VALUES (?, 'V10 숙소') RETURNING id", Long.class, orgId);
        Long unitId = sql.queryForObject(
                "INSERT INTO " + SCHEMA + ".unit (property_id, name, unit_kind, total_units, base_price) "
                        + "VALUES (?, '본채', 'ENTIRE_PLACE', 1, 100000) RETURNING id",
                Long.class, propertyId);
        sql.update(
                "INSERT INTO " + SCHEMA + ".inventory_ledger (unit_id, stay_date, total_units, booked_units, held_units) "
                        + "VALUES (?, DATE '2027-11-01', 2, 2, 0), (?, DATE '2027-11-02', 1, 1, 0)",
                unitId, unitId);
        assertThat(sql.queryForObject("SELECT max(version) FROM " + SCHEMA + ".flyway_schema_history", String.class))
                .isEqualTo("9");

        flyway("10").migrate();

        assertThat(sql.queryForObject(
                "SELECT success FROM " + SCHEMA + ".flyway_schema_history WHERE version = '10'", Boolean.class))
                .isTrue();
        Map<String, Object> 부푼행 = sql.queryForMap(
                "SELECT total_units, booked_units, overbooked_units FROM " + SCHEMA + ".inventory_ledger WHERE stay_date = DATE '2027-11-01'");
        assertThat(((Number) 부푼행.get("total_units")).intValue())
                .as("총수량은 판매 단위의 실제 수량으로 내려온다").isEqualTo(1);
        assertThat(((Number) 부푼행.get("booked_units")).intValue()).isEqualTo(2);
        assertThat(((Number) 부푼행.get("overbooked_units")).intValue())
                .as("초과분이 따로 드러난다").isEqualTo(1);
        Map<String, Object> 멀쩡한행 = sql.queryForMap(
                "SELECT total_units, overbooked_units FROM " + SCHEMA + ".inventory_ledger WHERE stay_date = DATE '2027-11-02'");
        assertThat(((Number) 멀쩡한행.get("total_units")).intValue()).isEqualTo(1);
        assertThat(((Number) 멀쩡한행.get("overbooked_units")).intValue()).isZero();

        // 새 제약이 살아 있다 — 등식을 깨는 쓰기는 거부된다.
        assertThatThrownBy(() -> sql.update(
                "UPDATE " + SCHEMA + ".inventory_ledger SET booked_units = 3 WHERE stay_date = DATE '2027-11-01'"))
                .isInstanceOf(DataIntegrityViolationException.class);

        sql.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
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
