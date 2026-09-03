package com.staysync.booking.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.staysync.booking.InventoryService;
import com.staysync.pricing.RateCalendarView;
import com.staysync.property.UnitCatalog;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.UnitSummary;
import com.staysync.property.domain.UnitKind;
import com.staysync.shared.audit.AuditLogRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 완료 조건 6. <b>요금과 판매중지가 함께 성공하거나 함께 실패한다.</b>
 *
 * <p>작업지시 06 이 "빠뜨리지 않는다"고 못박은 둘 중 하나다. 절반만 반영된 화면은
 * 정상으로 보인다 — 요금은 50만원으로 바뀌었는데 원장은 여전히 팔리는 상태이고,
 * 어긋난 것은 누가 그 값에 예약한 뒤에야 드러난다. 5~6주차의 트랜잭션 경계와 같은
 * 성질이다.
 *
 * <p>판매중지만 실패시키려면 빈을 바꿔야 하고, 그러면 스프링 컨텍스트 캐시 키가
 * 달라져 다른 테스트와 컨텍스트를 공유하지 못한다. 두 컨텍스트가 각자 내장 PostgreSQL 을
 * 띄우므로 포트와 데이터 디렉터리를 갈라야 한다({@code ApiTestBase} 와 같은 이유).
 * 그래서 이 테스트만 따로 있다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15435",
        "staysync.embedded-postgres.data-directory=.localdb-bulk"
})
@ActiveProfiles("local")
class BulkEditRollbackTest {

    private static final LocalDate 시작 = LocalDate.of(2027, 3, 1);
    private static final BigDecimal 기본요금 = new BigDecimal("90000");

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

    /** 판매중지만 실패시킨다. 요금 쓰기는 정상으로 두어 롤백 여부가 드러나게 한다. */
    @MockitoSpyBean
    private InventoryService inventoryService;

    @Test
    @DisplayName("판매중지가 실패하면 요금 변경과 감사 기록까지 함께 롤백된다")
    void 절반만_반영되지_않는다() {
        Long propertyId = 숙소();
        List<UnitSummary> units = unitCatalog.summariesOf(propertyId);
        List<Long> unitIds = units.stream().map(UnitSummary::id).toList();
        List<Long> ratePlanIds = units.stream().map(UnitSummary::defaultRatePlanId).toList();
        LocalDate 끝 = 시작.plusDays(4);
        long 감사_이전 = auditRepo.count();

        doThrow(new IllegalStateException("판매중지 갱신 실패를 흉내 낸다"))
                .when(inventoryService).changeStopSell(anyLong(), any(), anyBoolean());

        assertThatThrownBy(() -> bulkEditService.edit(propertyId, new BulkEdit.Request(
                unitIds, 시작, 끝, Set.of(),
                new BulkEdit.PriceChange.Fixed(new BigDecimal("500000")),
                null, null, true, false)))
                .isInstanceOf(IllegalStateException.class);

        // 요금이 남아 있으면 화면은 50만원인데 원장은 그대로 팔리는 상태다.
        assertThat(rateView.ratesOf(ratePlanIds, 시작, 끝)).isEmpty();

        CalendarGrid grid = calendarService.assemble(propertyId, 시작, 끝);
        assertThat(grid.units().get(0).days()).allSatisfy(cell -> {
            assertThat(cell.price()).isEqualByComparingTo(기본요금);
            assertThat(cell.stopSell()).isFalse();
        });

        // 감사 기록도 같은 트랜잭션이다. 하지 않은 일이 기록으로 남으면 안 된다.
        assertThat(auditRepo.count()).isEqualTo(감사_이전);
    }

    private Long 숙소() {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('롤백테스트') RETURNING id", Long.class);
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, '롤백 숙소') RETURNING id",
                Long.class, orgId);
        unitRegistration.register(propertyId, "롤백1", UnitKind.ENTIRE_PLACE, (short) 1, 기본요금);
        return propertyId;
    }
}
