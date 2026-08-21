package com.staysync.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.domain.InsufficientInventoryException;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import com.staysync.shared.lock.LockAcquisitionException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 중복예약 방지 검증.
 *
 * <p>계획서 P1 단계의 완료 조건이자, 평가 기준의 "기술적 난제 해결" 항목을 뒷받침하는
 * 근거다. Docker 없이 local 프로파일(내장 PostgreSQL)로 실행할 수 있다.
 */
@SpringBootTest(properties = {
        // 앱을 띄운 채 테스트를 돌려도 포트가 겹치지 않게 분리한다
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class InventoryConcurrencyTest {

    private static final int THREADS = 100;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("재고 1개에 동시 요청 100건이 들어와도 정확히 1건만 성공한다")
    void 재고가_하나면_동시요청_백건_중_한건만_성공한다() throws Exception {
        Long unitId = givenUnit("성수동 오피스텔", UnitKind.ENTIRE_PLACE, (short) 1);
        StayPeriod period = new StayPeriod(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 26));

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        inventoryService.reserve(unitId, period, 1);
                        success.incrementAndGet();
                    } catch (InsufficientInventoryException | LockAcquisitionException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        // 데이터베이스 제약 위반도 거절로 센다. 어느 계층이 막았든 결과는 같다.
                        rejected.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(THREADS - 1);
        assertThat(bookedUnits(unitId, period.checkIn())).isEqualTo(1);
    }

    @Test
    @DisplayName("도미토리 4자리에 동시 요청 100건이 들어오면 정확히 4건만 성공한다")
    void 재고가_넷이면_동시요청_백건_중_네건만_성공한다() throws Exception {
        Long unitId = givenUnit("4인 도미토리", UnitKind.SHARED_ROOM, (short) 4);
        StayPeriod period = new StayPeriod(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 26));

        AtomicInteger success = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        inventoryService.reserve(unitId, period, 1);
                        success.incrementAndGet();
                    } catch (Exception ignored) {
                        // 거절은 정상 동작이다
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(success.get()).isEqualTo(4);
        assertThat(bookedUnits(unitId, period.checkIn())).isEqualTo(4);
    }

    @Test
    @DisplayName("여러 날짜에 걸친 예약은 하루라도 모자라면 전체가 실패한다")
    void 하루라도_재고가_없으면_전체_예약이_실패한다() {
        Long unitId = givenUnit("작은방", UnitKind.PRIVATE_ROOM, (short) 1);

        StayPeriod first = new StayPeriod(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3));
        inventoryService.reserve(unitId, first, 1);      // 11/2 하루를 먼저 채운다

        StayPeriod spanning = new StayPeriod(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 4));

        try {
            inventoryService.reserve(unitId, spanning, 1);
            throw new AssertionError("11/2 재고가 없으므로 실패해야 한다");
        } catch (InsufficientInventoryException expected) {
            assertThat(expected.date()).isEqualTo(LocalDate.of(2026, 11, 2));
        }

        // 11/1 과 11/3 이 부분 차감되지 않았는지 확인한다. 트랜잭션이 통째로 롤백되어야 한다.
        assertThat(bookedUnits(unitId, LocalDate.of(2026, 11, 1))).isZero();
        assertThat(bookedUnits(unitId, LocalDate.of(2026, 11, 3))).isZero();
    }

    private Long givenUnit(String name, UnitKind kind, short totalUnits) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('테스트') RETURNING id", Long.class);
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        return unitRegistration.register(propertyId, name, kind, totalUnits, BigDecimal.valueOf(100000));
    }

    /**
     * 예약된 수량. 재고 행이 아예 없으면 0 으로 본다.
     *
     * <p>트랜잭션이 롤백되면 그 안에서 만들어진 재고 행도 함께 사라진다.
     * 이때 단순 조회는 빈 결과로 예외를 던지므로 COALESCE 로 감싼다.
     */
    private int bookedUnits(Long unitId, LocalDate date) {
        Integer booked = jdbc.queryForObject("""
                SELECT COALESCE(
                    (SELECT booked_units FROM inventory_ledger
                      WHERE unit_id = ? AND stay_date = ?), 0)
                """, Integer.class, unitId, date);
        return booked == null ? 0 : booked;
    }
}
