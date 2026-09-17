package com.staysync.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.domain.OverbookingConflict;
import com.staysync.channel.support.SyncTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 작업지시-18 B. 같은 (판매 단위, 날짜)의 초과 예약은 OPEN 충돌 한 행이다. 완료 조건 5·6·7.
 *
 * <p>채널 수신 경로({@code ChannelBookingIntake.ingest})로 초과 예약을 만든다 — 판매 단위
 * 락을 거치는 진입점이다. 예전에는 초과 예약이 둘이면 카드가 둘 뜨고, 먼저 생긴 카드는
 * 적은 예약을 담은 낡은 스냅샷으로 남았으며, 하나를 해소해도 다른 하나가 열려 있었다.
 * Channex 가 붙어 채널이 둘이 되는 날 바로 드러날 자리다.
 */
class ConflictDedupTest extends SyncTestBase {

    private static final LocalDate 체크인 = LocalDate.now().plusDays(30);
    private static final LocalDate 체크아웃 = 체크인.plusDays(1);

    @Autowired
    private ChannelBookingIntake intake;

    @Autowired
    private ConflictResolutionService conflictService;

    @Autowired
    private OverbookingConflictRepository conflicts;

    // --- 완료 조건 5 ------------------------------------------------------------

    @Test
    @DisplayName("같은 날짜에 초과 예약이 셋 들어와도 OPEN 충돌은 한 행이고 예약 셋을 담는다")
    void 초과가_셋이어도_충돌은_한_행이다() {
        Fixture f = given("충돌-중복");
        Long 정상 = intake.ingest(command(f, "D-0", 체크인, 체크아웃)).reservationId();
        Long 초과1 = intake.ingest(command(f, "D-1", 체크인, 체크아웃)).reservationId();
        Long 초과2 = intake.ingest(command(f, "D-2", 체크인, 체크아웃)).reservationId();
        Long 초과3 = intake.ingest(command(f, "D-3", 체크인, 체크아웃)).reservationId();

        List<OverbookingConflict> open = 열린충돌(f, 체크인);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getReservationIds())
                .as("카드 하나에 그날 겹치는 예약 전부 — 낡은 스냅샷이 아니다")
                .containsExactlyInAnyOrder(정상, 초과1, 초과2, 초과3);
    }

    // --- 완료 조건 6 ------------------------------------------------------------

    @Test
    @DisplayName("그 한 행을 해소하면 그날 OPEN 충돌이 없고, 뒤에 새 초과 예약이 오면 새 행이 생긴다")
    void 해소_뒤에는_새_행이다() {
        Fixture f = given("충돌-해소");
        intake.ingest(command(f, "R-0", 체크인, 체크아웃));
        Long 초과1 = intake.ingest(command(f, "R-1", 체크인, 체크아웃)).reservationId();
        intake.ingest(command(f, "R-2", 체크인, 체크아웃));
        OverbookingConflict 카드 = 열린충돌(f, 체크인).get(0);

        // 실제 추가 객실이 있어 흡수한다. 재고를 옮기지 않는 해소다.
        conflictService.resolve(카드.getId(), f.orgId(), 사용자(f),
                OverbookingConflict.ABSORBED, 초과1, null, "옆 방 있음");

        assertThat(열린충돌(f, 체크인)).as("하나를 해소했는데 다른 카드가 열려 있으면 안 된다").isEmpty();
        assertThat(conflicts.findByUnitIdOrderByStayDateAsc(f.unitId()))
                .as("해소된 행은 기록으로 남는다")
                .singleElement()
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(OverbookingConflict.RESOLVED));

        // 해소 뒤 또 초과 예약. 해소된 행은 건드리지 않고 새 OPEN 행이다.
        Long 초과3 = intake.ingest(command(f, "R-3", 체크인, 체크아웃)).reservationId();
        List<OverbookingConflict> open = 열린충돌(f, 체크인);
        assertThat(open).hasSize(1);
        assertThat(open.get(0).getId()).isNotEqualTo(카드.getId());
        assertThat(open.get(0).getReservationIds()).contains(초과3);
        assertThat(conflicts.findByUnitIdOrderByStayDateAsc(f.unitId())).hasSize(2);
    }

    // --- 완료 조건 7 ------------------------------------------------------------

    @Test
    @DisplayName("같은 예약의 날짜 수정이 두 번 와도 충돌이 늘지 않는다")
    void 수정이_두_번_와도_늘지_않는다() {
        // 판매중지한 날이다. 초과 판매는 forceBook 이 원장의 총 수량을 올려 버려서 같은
        // 예약의 두 번째 수정에서는 그 날이 더는 모자라지 않지만(반납한 자리가 남는다),
        // 판매중지는 매번 "못 판다"라 수정 경로가 raiseConflicts 를 두 번 지난다.
        Fixture f = given("충돌-수정");
        intake.ingest(command(f, "M-0", 체크인, 체크아웃));
        inventory.changeStopSell(f.unitId(), List.of(체크인, 체크아웃), true);
        Long 옮길것 = intake.ingest(command(f, "M-1", 체크인.plusDays(5), 체크인.plusDays(6), 1))
                .reservationId();
        assertThat(열린충돌(f, 체크인)).isEmpty();

        intake.ingest(command(f, "M-1", 체크인, 체크아웃, 2));
        assertThat(열린충돌(f, 체크인)).hasSize(1);
        intake.ingest(command(f, "M-1", 체크인, 체크아웃.plusDays(1), 3));

        List<OverbookingConflict> open = 열린충돌(f, 체크인);
        assertThat(open).as("같은 (판매 단위, 날짜)에 OPEN 은 하나다").hasSize(1);
        assertThat(open.get(0).getReservationIds()).contains(옮길것);
        // 하루 늘린 밤도 판매중지라 그날은 새 행 하나 — 날짜당 한 행이다.
        assertThat(열린충돌(f, 체크아웃)).hasSize(1);
        assertThat(conflicts.findByUnitIdOrderByStayDateAsc(f.unitId())).hasSize(2);
    }

    // --- 픽스처 -----------------------------------------------------------------

    private ChannelBookingCommand command(Fixture f, String bookingId,
                                          LocalDate checkIn, LocalDate checkOut) {
        return command(f, bookingId, checkIn, checkOut, 1);
    }

    private ChannelBookingCommand command(Fixture f, String bookingId,
                                          LocalDate checkIn, LocalDate checkOut, int revision) {
        // 채널 예약번호는 (channel_code, channel_booking_id) 로 전역 유일이라 픽스처마다 갈라야 한다.
        return new ChannelBookingCommand(f.propertyId(), f.unitId(), "MOCK_DEDUP",
                bookingId + "-" + f.unitId(), null, checkIn, checkOut,
                BigDecimal.valueOf(100_000), revision, false);
    }

    private List<OverbookingConflict> 열린충돌(Fixture f, LocalDate date) {
        return conflicts.findOpenIn(f.propertyId(), date, date);
    }

    private Long 사용자(Fixture f) {
        return jdbc.queryForObject("""
                INSERT INTO user_account (org_id, email, password_hash, display_name, role)
                VALUES (?, ?, 'x', '운영자', 'OWNER') RETURNING id
                """, Long.class, f.orgId(), "dedup-" + f.orgId() + "@example.com");
    }
}
