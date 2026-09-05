package com.staysync.booking.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.ChannelBookingCommand;
import com.staysync.booking.ChannelBookingIntake;
import com.staysync.booking.ChannelBookingResult;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 완료 조건 13. 캘린더의 {@code conflict} 가 실제 값을 갖는다.
 *
 * <p>7주차에 항상 {@code false} 로 두고 "P3 에서 채운다"고 주석을 남긴 자리다. 화면은
 * 이미 이 값을 보고 셀을 표시하도록 짜여 있어 프론트엔드를 손대지 않는다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class CalendarConflictTest {

    private static final LocalDate 체크인 = LocalDate.of(2027, 8, 1);
    private static final LocalDate 체크아웃 = LocalDate.of(2027, 8, 3);

    @Autowired
    private ChannelBookingIntake intake;

    @Autowired
    private CalendarService calendar;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("초과 판매가 기록된 셀은 conflict 가 true 다")
    void 충돌_셀이_표시된다() {
        Long propertyId = 숙소();
        Long unitId = unitRegistration.register(propertyId, "충돌 객실",
                UnitKind.ENTIRE_PLACE, (short) 1, BigDecimal.valueOf(100_000));

        intake.ingest(command(propertyId, unitId, "BK-A"));
        ChannelBookingResult 초과 = intake.ingest(command(propertyId, unitId, "BK-B"));
        assertThat(초과.outcome()).isEqualTo(ChannelBookingResult.Outcome.CONFLICT);

        CalendarGrid grid = calendar.assemble(propertyId, 체크인.minusDays(1), 체크아웃);

        // 숙박한 두 밤만 충돌이다. 체크아웃일은 재고를 차지하지 않으므로 표시하지 않는다.
        assertThat(cell(grid, 체크인.minusDays(1)).conflict()).isFalse();
        assertThat(cell(grid, 체크인).conflict()).isTrue();
        assertThat(cell(grid, 체크인.plusDays(1)).conflict()).isTrue();
        assertThat(cell(grid, 체크아웃).conflict()).isFalse();
    }

    @Test
    @DisplayName("충돌이 없으면 conflict 는 false 다")
    void 정상_셀은_표시되지_않는다() {
        Long propertyId = 숙소();
        Long unitId = unitRegistration.register(propertyId, "정상 객실",
                UnitKind.ENTIRE_PLACE, (short) 2, BigDecimal.valueOf(100_000));

        intake.ingest(command(propertyId, unitId, "BK-OK"));

        CalendarGrid grid = calendar.assemble(propertyId, 체크인, 체크아웃);
        assertThat(grid.units().get(0).days()).allSatisfy(
                day -> assertThat(day.conflict()).isFalse());
    }

    private static CalendarGrid.DayCell cell(CalendarGrid grid, LocalDate date) {
        return grid.units().get(0).days().stream()
                .filter(day -> day.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new AssertionError("셀이 없다: " + date));
    }

    private Long 숙소() {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('충돌표시') RETURNING id", Long.class);
        return jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, '충돌 숙소') RETURNING id",
                Long.class, orgId);
    }

    private static ChannelBookingCommand command(Long propertyId, Long unitId, String bookingId) {
        return new ChannelBookingCommand(propertyId, unitId, "MOCK_CF", bookingId,
                체크인, 체크아웃, BigDecimal.valueOf(200_000), 1, false);
    }
}
