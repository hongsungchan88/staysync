package com.staysync.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.staysync.booking.domain.ConflictAlreadyResolvedException;
import com.staysync.booking.domain.OverbookingConflict;
import com.staysync.channel.support.SyncTestBase;
import com.staysync.property.domain.UnitKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * <b>완료 조건 14·15.</b> 충돌 해소. 계획서 7.4 이고 방어 4계층의 마지막 자리다.
 *
 * <p>15번이 이 파일의 핵심이다. <b>업그레이드 배정은 락을 둘 쥔다</b> — ADR 0002 의
 * 결과 절이 "락이 둘이 되는 곳이 생기면 순서를 정하라"고 예고해 둔 자리다. 순서가
 * 틀려도 평소에는 아무 일이 없고, 두 요청이 맞물리는 그 순간에만 교착이 난다.
 */
class ConflictResolutionTest extends SyncTestBase {

    private static final LocalDate 체크인 = LocalDate.now().plusDays(20);
    private static final LocalDate 체크아웃 = 체크인.plusDays(2);

    @Autowired
    private ConflictResolutionService conflictService;

    @Autowired
    private ChannelBookingIntake intake;

    @Autowired
    private OverbookingConflictRepository conflicts;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private BookingService bookings;

    // --- 15. 락 순서 -------------------------------------------------------------

    @Test
    @DisplayName("업그레이드 배정은 판매 단위 식별자 오름차순으로 잠근다")
    void 락을_둘_쥘_때_식별자_오름차순이다() {
        // 요청한 순서(옛 단위 → 새 단위)로 잡으면 A→B 와 B→A 가 맞물리는 순간
        // 서로가 서로를 기다린다. 어느 방향으로 옮기든 잠그는 순서는 같아야 한다.
        assertThat(ConflictResolutionService.lockOrder(7L, 3L)).containsExactly(3L, 7L);
        assertThat(ConflictResolutionService.lockOrder(3L, 7L)).containsExactly(3L, 7L);

        // 같은 단위면 하나다. 두 번 잡아도 ReentrantLock 이라 재진입이지만,
        // 목록에 둘이 남으면 "둘을 잡는다"는 사실이 흐려진다.
        assertThat(ConflictResolutionService.lockOrder(5L, 5L)).containsExactly(5L);
    }

    @Test
    @DisplayName("서로 반대 방향으로 옮기는 두 요청이 맞물려도 교착이 나지 않는다")
    void 교차_업그레이드가_멈추지_않는다() throws Exception {
        // 방마다 여유가 하나씩 있어야 서로 맞바꿀 수 있다. 둘 다 꽉 차 있으면
        // 교착이 아니라 재고 부족으로 실패하고, 그러면 락 순서를 검증하지 못한다.
        Fixture f = given("교차 업그레이드", (short) 2);
        Long 별채 = 판매단위(f, "별채", (short) 2);
        Long 본채 = f.unitId();

        // 본채에 예약 하나, 별채에 예약 하나. 서로 상대 방으로 옮긴다.
        // 충돌 행은 직접 넣는다 — 여기서 재는 것은 락 순서이지 충돌이 생기는
        // 경로가 아니고, 그건 다른 테스트가 본다.
        Long 예약1 = 수기예약(f, 본채);
        Long 예약2 = 수기예약(f, 별채);
        Long 충돌1 = 충돌행(f, 본채, 예약1);
        Long 충돌2 = 충돌행(f, 별채, 예약2);

        // 두 요청이 정확히 같은 순간에 시작하게 한다. 락 순서가 요청 순서라면
        // 하나는 본채를, 다른 하나는 별채를 먼저 잡고 서로를 기다린다.
        // LocalUnitLock 은 3초 tryLock 이라 교착은 LockAcquisitionException 으로 드러난다.
        CountDownLatch 출발 = new CountDownLatch(1);
        AtomicInteger 성공 = new AtomicInteger();
        // 계정은 미리 만든다. 두 스레드가 동시에 만들면 이메일 유니크에 걸려
        // 교착이 아닌 이유로 실패한다.
        Long 운영자 = 사용자(f);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> a = pool.submit(() -> 옮긴다(출발, 충돌1, f, 운영자, 예약1, 별채, 성공));
            Future<?> b = pool.submit(() -> 옮긴다(출발, 충돌2, f, 운영자, 예약2, 본채, 성공));
            출발.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        }

        assertThat(성공.get()).as("둘 다 끝나야 한다. 하나라도 락을 못 잡으면 교착이다").isEqualTo(2);
    }

    // --- 14. 해소 기록 -----------------------------------------------------------

    @Test
    @DisplayName("업그레이드로 해소하면 예약이 다른 판매 단위로 옮겨지고 기록이 남는다")
    void 업그레이드_배정이_재고를_옮긴다() {
        Fixture f = given("업그레이드", (short) 1);
        Long 별채 = 판매단위(f, "별채");
        Long 예약 = 충돌예약(f, f.unitId(), "U-1");
        Long 충돌 = 충돌하나(f.unitId());

        OverbookingConflict 해소 = conflictService.resolve(
                충돌, f.orgId(), 사용자(f), OverbookingConflict.UPGRADED, 예약, 별채, "상위 방으로 배정");

        assertThat(reservations.findById(예약).orElseThrow().getUnitId())
                .as("예약이 실제로 옮겨져야 한다. 기록만 남기면 재고가 거짓말을 한다")
                .isEqualTo(별채);
        assertThat(해소.getStatus()).isEqualTo(OverbookingConflict.RESOLVED);
        assertThat(해소.getResolution()).isEqualTo(OverbookingConflict.UPGRADED);
        assertThat(해소.getResolvedBy()).isEqualTo(사용자(f));
        assertThat(해소.getResolvedAt()).isNotNull();
        assertThat(해소.getMemo()).isEqualTo("상위 방으로 배정");
    }

    @Test
    @DisplayName("취소로 해소하면 예약이 취소되고 재고가 돌아온다")
    void 취소로_해소한다() {
        Fixture f = given("취소 해소", (short) 1);
        Long 예약 = 충돌예약(f, f.unitId(), "C-1");
        Long 충돌 = 충돌하나(f.unitId());

        conflictService.resolve(충돌, f.orgId(), 사용자(f),
                OverbookingConflict.CANCELLED, 예약, null, "보상 후 취소");

        assertThat(reservations.findById(예약).orElseThrow().getStatus().name())
                .isEqualTo("CANCELLED");
        assertThat(conflicts.findById(충돌).orElseThrow().getResolution())
                .isEqualTo(OverbookingConflict.CANCELLED);
    }

    @Test
    @DisplayName("제휴 숙소 안내와 관리자 허용은 재고를 건드리지 않고 기록만 남긴다")
    void 재고를_옮기지_않는_해소도_기록된다() {
        Fixture f = given("기록만", (short) 1);
        Long 예약 = 충돌예약(f, f.unitId(), "R-1");
        Long 충돌 = 충돌하나(f.unitId());

        conflictService.resolve(충돌, f.orgId(), 사용자(f),
                OverbookingConflict.ABSORBED, 예약, null, "실제 여유 객실이 있다");

        assertThat(reservations.findById(예약).orElseThrow().getStatus().name())
                .as("허용은 예약을 그대로 둔다")
                .isEqualTo("CONFIRMED");
        assertThat(conflicts.findById(충돌).orElseThrow().getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 해소된 충돌은 다시 해소되지 않는다")
    void 두_번_해소되지_않는다() {
        // 화면에서 두 번 눌리거나 두 사람이 동시에 처리하면 방이 두 번 옮겨진다.
        Fixture f = given("중복 해소", (short) 1);
        Long 별채 = 판매단위(f, "별채");
        Long 예약 = 충돌예약(f, f.unitId(), "D-1");
        Long 충돌 = 충돌하나(f.unitId());

        conflictService.resolve(충돌, f.orgId(), 사용자(f),
                OverbookingConflict.UPGRADED, 예약, 별채, null);

        assertThatThrownBy(() -> conflictService.resolve(충돌, f.orgId(), 사용자(f),
                OverbookingConflict.ABSORBED, 예약, null, null))
                .isInstanceOf(ConflictAlreadyResolvedException.class);
    }

    @Test
    @DisplayName("해소한 충돌은 목록에서 빠진다")
    void 해소하면_목록에서_사라진다() {
        Fixture f = given("목록", (short) 1);
        Long 예약 = 충돌예약(f, f.unitId(), "L-1");
        Long 충돌 = 충돌하나(f.unitId());

        assertThat(conflictService.listOpen(f.orgId()))
                .extracting(view -> view.conflict().getId())
                .contains(충돌);
        // 목록에 부딪힌 예약이 함께 담긴다. 식별자만 주면 화면이 하나씩 다시 조회한다.
        assertThat(conflictService.listOpen(f.orgId()).stream()
                .filter(view -> view.conflict().getId().equals(충돌))
                .findFirst().orElseThrow().reservations())
                .isNotEmpty();

        conflictService.resolve(충돌, f.orgId(), 사용자(f),
                OverbookingConflict.RELOCATED, 예약, null, null);

        assertThat(conflictService.listOpen(f.orgId()))
                .extracting(view -> view.conflict().getId())
                .doesNotContain(충돌);
    }

    @Test
    @DisplayName("남의 조직 충돌은 보이지도 해소되지도 않는다")
    void 남의_충돌은_404_다() {
        Fixture 주인 = given("주인", (short) 1);
        Long 예약 = 충돌예약(주인, 주인.unitId(), "O-1");
        Long 충돌 = 충돌하나(주인.unitId());
        Fixture 남 = given("남", (short) 1);

        assertThat(conflictService.listOpen(남.orgId()))
                .extracting(view -> view.conflict().getId())
                .doesNotContain(충돌);
        assertThatThrownBy(() -> conflictService.resolve(충돌, 남.orgId(), 사용자(남),
                OverbookingConflict.ABSORBED, 예약, null, null))
                .isInstanceOf(ConflictNotFoundException.class);
    }

    // --- 픽스처 -------------------------------------------------------------------

    private void 옮긴다(CountDownLatch 출발, Long 충돌, Fixture f, Long 운영자, Long 예약,
                    Long 대상, AtomicInteger 성공) {
        try {
            출발.await();
            conflictService.resolve(충돌, f.orgId(), 운영자,
                    OverbookingConflict.UPGRADED, 예약, 대상, null);
            성공.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Long 판매단위(Fixture f, String name) {
        return 판매단위(f, name, (short) 1);
    }

    private Long 판매단위(Fixture f, String name, short totalUnits) {
        return unitRegistration.register(f.propertyId(), name, UnitKind.ENTIRE_PLACE,
                totalUnits, BigDecimal.valueOf(150_000));
    }

    private Long 수기예약(Fixture f, Long unitId) {
        return bookings.registerManual(f.propertyId(), unitId,
                new com.staysync.booking.domain.StayPeriod(체크인, 체크아웃),
                BigDecimal.valueOf(200_000), (short) 2, (short) 0, null).getId();
    }

    /**
     * 충돌 행을 직접 넣는다.
     *
     * <p>교착 검증에만 쓴다. 재고를 넘겨 만들려면 두 방이 모두 꽉 차야 하는데,
     * 그러면 서로 맞바꿀 자리가 없어 <b>교착이 아니라 재고 부족으로 실패한다.</b>
     * 충돌이 실제로 생기는 경로는 {@code 충돌예약} 이 쓰는 수신 경로가 본다.
     */
    private Long 충돌행(Fixture f, Long unitId, Long reservationId) {
        return jdbc.queryForObject("""
                INSERT INTO overbooking_conflict
                    (property_id, unit_id, stay_date, reservation_ids, severity)
                VALUES (?, ?, ?, ARRAY[?]::bigint[], 'CRITICAL') RETURNING id
                """, Long.class, f.propertyId(), unitId, 체크인, reservationId);
    }

    /**
     * 재고를 넘긴 채널 예약을 하나 만든다. {@code overbooking_conflict} 행이 생긴다.
     *
     * <p>수기 예약으로 방을 먼저 채우고 채널 예약을 밀어 넣는다. 12주차의 "OTA 에서
     * 이미 성사된 예약은 거절하지 않는다"가 그대로 도는 경로다.
     */
    private Long 충돌예약(Fixture f, Long unitId, String 채널예약번호) {
        bookings.registerManual(f.propertyId(), unitId,
                new com.staysync.booking.domain.StayPeriod(체크인, 체크아웃),
                BigDecimal.valueOf(200_000), (short) 2, (short) 0, null);
        ChannelBookingResult result = intake.ingest(new ChannelBookingCommand(
                f.propertyId(), unitId, "MOCK_CONFLICT", 채널예약번호,
                체크인, 체크아웃, BigDecimal.valueOf(200_000), 1, false));
        assertThat(result.outcome())
                .as("재고를 넘겨 받아들여야 충돌이 생긴다")
                .isEqualTo(ChannelBookingResult.Outcome.CONFLICT);
        return result.reservationId();
    }

    private Long 충돌하나(Long unitId) {
        List<OverbookingConflict> found = conflicts.findByUnitIdOrderByStayDateAsc(unitId);
        assertThat(found).isNotEmpty();
        return found.get(0).getId();
    }

    /**
     * 해소한 사람. {@code resolved_by} 가 {@code user_account} 를 참조하므로 실제 행이
     * 있어야 한다. 조직마다 하나만 만든다.
     */
    private Long 사용자(Fixture f) {
        List<Long> found = jdbc.queryForList(
                "SELECT id FROM user_account WHERE org_id = ? ORDER BY id LIMIT 1",
                Long.class, f.orgId());
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO user_account (org_id, email, password_hash, display_name, role)
                VALUES (?, ?, 'x', '운영자', 'OWNER') RETURNING id
                """, Long.class, f.orgId(), "conflict-" + f.orgId() + "@example.com");
    }
}
