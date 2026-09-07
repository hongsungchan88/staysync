package com.staysync.booking.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.staysync.pricing.RateCalendarView;
import com.staysync.property.UnitCatalog;
import com.staysync.property.UnitNotFoundException;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.UnitSummary;
import com.staysync.property.domain.UnitKind;
import com.staysync.shared.audit.AuditLog;
import com.staysync.shared.audit.AuditLogRepository;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 완료 조건 1~5, 7, 8. 요금·제약 일괄 편집.
 *
 * <p>1번이 계획서 12.2 가 말하는 P2 완료 조건의 절반이다 — "30일 요금을 한 번에 변경할
 * 수 있는 상태". 바뀌었다는 것만으로는 부족하고 <b>캘린더 재조회에 그 값이 나와야</b>
 * 한다. 조립부가 요금을 읽는 경로와 편집이 쓰는 경로가 어긋나면 여기서 드러난다.
 *
 * <p>6번(트랜잭션 롤백)은 {@link BulkEditRollbackTest} 에 따로 있다. 그쪽은 빈을 바꿔야
 * 해서 스프링 컨텍스트가 갈리기 때문이다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class BulkEditTest {

    /** 3월 1일은 월요일이다. 요일 필터 테스트가 이 사실에 기댄다. */
    private static final LocalDate 시작 = LocalDate.of(2027, 3, 1);
    static final BigDecimal 기본요금 = new BigDecimal("90000");

    @Autowired
    private BulkEditService bulkEditService;
    @Autowired
    private CalendarService calendarService;
    @Autowired
    private RateCalendarView rateView;
    @Autowired
    private UnitCatalog unitCatalog;
    @Autowired
    private UnitRegistrationService unitRegistration;
    @Autowired
    private AuditLogRepository auditRepo;
    @Autowired
    private JdbcTemplate jdbc;

    // --- 완료 조건 1 ---------------------------------------------------------

    @Test
    @DisplayName("30일 요금을 한 번에 바꾸면 캘린더 재조회에 그 값이 나온다")
    void 삼십일_요금을_한_번에_바꾼다() {
        Fixture f = given("일괄편집", 2);
        LocalDate 끝 = 시작.plusDays(29);

        BulkEdit.Result result = bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 끝, Set.of(),
                new BulkEdit.PriceChange.Fixed(new BigDecimal("250000")),
                null, null, null, false));

        assertThat(result.dayCount()).isEqualTo(30);
        assertThat(result.cellCount()).isEqualTo(60);   // 판매 단위 2개 × 30일

        // 계획서 12.2 가 요구하는 것은 "바뀌었다"가 아니라 "화면에 그렇게 보인다"이다.
        CalendarGrid grid = calendarService.assemble(f.propertyId(), 시작, 끝);
        assertThat(grid.units()).hasSize(2);
        for (CalendarGrid.UnitRow row : grid.units()) {
            assertThat(row.days()).hasSize(30);
            assertThat(row.days()).allSatisfy(cell ->
                    assertThat(cell.price()).isEqualByComparingTo("250000"));
        }
    }

    // --- 완료 조건 2 ---------------------------------------------------------

    @Test
    @DisplayName("요일 필터가 걸리면 그 요일만 바뀐다")
    void 요일_필터가_걸린_요일만_바뀐다() {
        Fixture f = given("요일필터", 1);
        LocalDate 끝 = 시작.plusDays(20);

        bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 끝, Set.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
                new BulkEdit.PriceChange.Fixed(new BigDecimal("300000")),
                null, null, null, false));

        CalendarGrid grid = calendarService.assemble(f.propertyId(), 시작, 끝);
        for (CalendarGrid.DayCell cell : grid.units().get(0).days()) {
            boolean 주말 = cell.date().getDayOfWeek() == DayOfWeek.FRIDAY
                    || cell.date().getDayOfWeek() == DayOfWeek.SATURDAY;
            assertThat(cell.price())
                    .as("%s(%s)", cell.date(), cell.date().getDayOfWeek())
                    .isEqualByComparingTo(주말 ? new BigDecimal("300000") : 기본요금);
        }
    }

    // --- 완료 조건 3 ---------------------------------------------------------

    @Test
    @DisplayName("기존 대비 +20% 는 셀마다 다른 기준가에 각각 붙는다")
    void 퍼센트는_셀마다_다른_기준가에_붙는다() {
        Fixture f = given("퍼센트", 1);
        LocalDate 끝 = 시작.plusDays(6);

        // 먼저 토요일만 다른 요금으로 만든다. 이게 없으면 "전부 같은 값"과 구분되지 않는다.
        bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 끝, Set.of(DayOfWeek.SATURDAY),
                new BulkEdit.PriceChange.Fixed(new BigDecimal("200000")),
                null, null, null, false));

        bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 끝, Set.of(),
                new BulkEdit.PriceChange.Percent(new BigDecimal("0.20")),
                null, null, null, false));

        CalendarGrid grid = calendarService.assemble(f.propertyId(), 시작, 끝);
        for (CalendarGrid.DayCell cell : grid.units().get(0).days()) {
            String expected = cell.date().getDayOfWeek() == DayOfWeek.SATURDAY
                    ? "240000"      // 200,000 × 1.2
                    : "108000";     // 90,000 × 1.2
            assertThat(cell.price())
                    .as("%s 의 요금", cell.date())
                    .isEqualByComparingTo(expected);
        }

        // 전부 같은 값이 되면 기준가를 한 번만 읽은 것이다. 그 실수를 여기서 잡는다.
        assertThat(grid.units().get(0).days().stream()
                .map(CalendarGrid.DayCell::price)
                .map(BigDecimal::stripTrailingZeros)
                .distinct()
                .count()).isEqualTo(2);
    }

    // --- 완료 조건 4·5 -------------------------------------------------------

    @Test
    @DisplayName("dryRun 의 건수가 실제 적용 건수와 같다")
    void 미리보기_건수와_적용_건수가_같다() {
        Fixture f = given("미리보기건수", 3);
        LocalDate 끝 = 시작.plusDays(13);
        Set<DayOfWeek> 월화 = Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY);
        BulkEdit.PriceChange 고정 = new BulkEdit.PriceChange.Fixed(new BigDecimal("111100"));

        BulkEdit.Result 예상 = bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 끝, 월화, 고정, null, null, null, true));
        BulkEdit.Result 실제 = bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 끝, 월화, 고정, null, null, null, false));

        assertThat(예상.cellCount()).isEqualTo(실제.cellCount());
        assertThat(예상.dayCount()).isEqualTo(실제.dayCount());
        assertThat(예상.unitCount()).isEqualTo(실제.unitCount());
        assertThat(예상.changed()).isEqualTo(실제.changed());
        // 2주 동안 월·화가 각각 두 번이다. 3단위 × 4일 = 12셀.
        assertThat(예상.cellCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("dryRun 은 요금도 원장도 감사 로그도 건드리지 않는다")
    void 미리보기는_아무것도_쓰지_않는다() {
        Fixture f = given("미리보기무해", 1);
        LocalDate 끝 = 시작.plusDays(9);
        long 감사_이전 = auditRepo.count();

        BulkEdit.Result result = bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 끝, Set.of(),
                new BulkEdit.PriceChange.Fixed(new BigDecimal("999900")),
                (short) 5, true, true, true));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.cellCount()).isEqualTo(10);

        // 요금 행이 아예 생기지 않아야 한다.
        assertThat(rateView.ratesOf(f.ratePlanIds(), 시작, 끝)).isEmpty();

        // 원장의 판매중지도 켜지지 않아야 한다.
        CalendarGrid grid = calendarService.assemble(f.propertyId(), 시작, 끝);
        assertThat(grid.units().get(0).days()).allSatisfy(cell -> {
            assertThat(cell.stopSell()).isFalse();
            assertThat(cell.price()).isEqualByComparingTo(기본요금);
            assertThat(cell.minStay()).isEqualTo((short) 1);
        });

        // 감사 로그도 늘지 않아야 한다. 미리보기는 "무슨 일이 있었다"가 아니다.
        assertThat(auditRepo.count()).isEqualTo(감사_이전);
    }

    // --- 완료 조건 7 ---------------------------------------------------------

    @Test
    @DisplayName("일괄 편집이 감사 로그에 한 줄로 남고 범위와 항목이 들어 있다")
    void 감사_로그에_한_줄로_남는다() {
        Fixture f = given("감사", 2);
        LocalDate 끝 = 시작.plusDays(29);
        long 이전 = auditRepo.count();

        bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 끝, Set.of(DayOfWeek.FRIDAY),
                new BulkEdit.PriceChange.Percent(new BigDecimal("0.20")),
                (short) 2, null, true, false));

        // 셀마다 남기면 여기서만 여덟 줄이고, 30일 × 10단위면 300줄이다.
        // 그러면 정작 봐야 할 예약 변경 기록이 묻힌다.
        assertThat(auditRepo.count()).isEqualTo(이전 + 1);

        List<AuditLog> logs = auditRepo.findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
                "RATE_CALENDAR", f.propertyId());
        assertThat(logs).hasSize(1);

        AuditLog log = logs.get(0);
        assertThat(log.getAction()).isEqualTo("RATE_BULK_EDIT");
        assertThat(log.getAfterValue())
                .contains(시작.toString())
                .contains(끝.toString())
                .contains("FRIDAY")
                .contains("price")
                .contains("minStay")
                .contains("stopSell");
    }

    // --- 확인-05 3절 B. 이벤트에 날짜 범위 -------------------------------------

    /**
     * 확인-05 완료 조건 4. <b>이벤트를 만드는 쪽</b>을 고정한다.
     *
     * <p>범위 없이 만들어진 이벤트는 채널 전파가 포기한다 — 화면에는 값이 바뀐 것으로
     * 보이고 채널에는 안 나간다. 전파하는 쪽에서 추측으로 메우면 틀린 구간을 밀게
     * 되므로 <b>범위는 여기서 실려야 한다.</b>
     *
     * <p>날짜는 요청의 {@code from}/{@code to} 가 아니라 <b>요일 필터를 거친 실제
     * 대상</b>의 양끝이다. 3월 1일이 월요일이므로 토요일만 고르면 3월 6일부터다.
     */
    @Test
    @DisplayName("일괄 편집 이벤트에 판매 단위와 날짜 범위가 실린다")
    void 이벤트에_범위가_실린다() {
        Fixture f = given("이벤트범위", 2);
        LocalDate 끝 = 시작.plusDays(29);

        bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 끝, Set.of(DayOfWeek.SATURDAY),
                new BulkEdit.PriceChange.Fixed(new BigDecimal("150000")),
                null, null, null, false));

        String payload = jdbc.queryForObject("""
                SELECT payload::text FROM outbox_event
                WHERE aggregate_type = 'RATE_CALENDAR' AND aggregate_id = ?
                  AND event_type = 'RATE_BULK_EDITED'
                ORDER BY id DESC LIMIT 1
                """, String.class, f.propertyId());

        assertThat(payload)
                .as("범위가 없으면 ChannelSyncService 가 전파를 포기한다")
                .contains("\"unitIds\"")
                .contains("\"from\"")
                .contains("\"to\"");
        // 요청 범위를 그대로 실으면 평일까지 전파 구간에 들어간다.
        assertThat(payload)
                .contains(시작.plusDays(5).toString())      // 첫 토요일 3/6
                .contains(시작.plusDays(26).toString())     // 마지막 토요일 3/27
                .doesNotContain(시작.toString());
        for (Long unitId : f.unitIds()) {
            assertThat(payload).contains(unitId.toString());
        }
    }

    // --- 완료 조건 8 ---------------------------------------------------------

    @Test
    @DisplayName("다른 숙소의 판매 단위를 적용 대상에 넣으면 거부된다")
    void 남의_판매_단위는_적용_대상이_될_수_없다() {
        Fixture 내것 = given("내숙소", 1);
        Fixture 남의것 = given("남의숙소", 1);

        // 컨트롤러의 조직 스코핑을 통과했더라도, 그 숙소에 속하지 않는 판매 단위는
        // 여기서 걸려야 한다. 두 겹이 있어야 적용 대상에 끼워 넣을 수 없다.
        assertThatThrownBy(() -> bulkEditService.edit(내것.propertyId(), new BulkEdit.Request(
                List.of(내것.unitIds().get(0), 남의것.unitIds().get(0)),
                시작, 시작.plusDays(2), Set.of(),
                new BulkEdit.PriceChange.Fixed(new BigDecimal("100000")),
                null, null, null, false)))
                .isInstanceOf(UnitNotFoundException.class);

        // 거부됐으니 내 숙소의 요금도 바뀌지 않아야 한다.
        assertThat(rateView.ratesOf(내것.ratePlanIds(), 시작, 시작.plusDays(2))).isEmpty();
    }

    // --- 그 밖의 계약 ---------------------------------------------------------

    @Test
    @DisplayName("판매중지를 켜면 캘린더 셀에 나타난다")
    void 판매중지가_셀에_나타난다() {
        Fixture f = given("판매중지", 1);
        LocalDate 끝 = 시작.plusDays(4);

        bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 끝, Set.of(), null, null, null, true, false));

        CalendarGrid grid = calendarService.assemble(f.propertyId(), 시작, 끝);
        assertThat(grid.units().get(0).days()).allSatisfy(cell ->
                assertThat(cell.stopSell()).isTrue());
    }

    @Test
    @DisplayName("최소 숙박만 바꾸면 요금은 기본값 그대로다")
    void 최소_숙박만_바꾸면_요금은_그대로다() {
        Fixture f = given("최소숙박", 1);
        LocalDate 끝 = 시작.plusDays(4);

        bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 끝, Set.of(), null, (short) 3, null, null, false));

        CalendarGrid grid = calendarService.assemble(f.propertyId(), 시작, 끝);
        assertThat(grid.units().get(0).days()).allSatisfy(cell -> {
            assertThat(cell.minStay()).isEqualTo((short) 3);
            // 요금 행이 새로 생기지만 값은 기본 요금이어야 한다. 여기가 틀리면 최소 숙박만
            // 바꿨는데 요금이 0 이 되거나 엉뚱한 값이 된다.
            assertThat(cell.price()).isEqualByComparingTo(기본요금);
        });
    }

    @Test
    void 바꿀_항목이_없으면_거부한다() {
        assertThatThrownBy(() -> new BulkEdit.Request(
                List.of(1L), 시작, 시작.plusDays(1), Set.of(),
                null, null, null, null, false))
                .isInstanceOf(InvalidBulkEditException.class)
                .hasMessageContaining("바꿀 항목이 하나도 없습니다");
    }

    @Test
    void 요일_필터에_해당하는_날이_없으면_거부한다() {
        Fixture f = given("빈요일", 1);

        // 3/1 은 월요일이다. 이틀 범위에 일요일은 없다.
        assertThatThrownBy(() -> bulkEditService.edit(f.propertyId(), new BulkEdit.Request(
                f.unitIds(), 시작, 시작.plusDays(1), Set.of(DayOfWeek.SUNDAY),
                new BulkEdit.PriceChange.Fixed(new BigDecimal("100000")),
                null, null, null, false)))
                .isInstanceOf(InvalidBulkEditException.class)
                .hasMessageContaining("해당하는 날짜가 없습니다");
    }

    // --- 픽스처 --------------------------------------------------------------

    /** 테스트마다 새 숙소를 쓴다. 앞 테스트가 남긴 요금이 뒤에 섞이면 안 된다. */
    private Fixture given(String name, int unitCount) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('일괄편집테스트') RETURNING id", Long.class);
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        for (int i = 0; i < unitCount; i++) {
            unitRegistration.register(propertyId, name + (i + 1),
                    UnitKind.ENTIRE_PLACE, (short) 1, 기본요금);
        }

        List<UnitSummary> units = unitCatalog.summariesOf(propertyId);
        return new Fixture(propertyId,
                units.stream().map(UnitSummary::id).toList(),
                units.stream().map(UnitSummary::defaultRatePlanId).toList());
    }

    record Fixture(Long propertyId, List<Long> unitIds, List<Long> ratePlanIds) {
    }
}
