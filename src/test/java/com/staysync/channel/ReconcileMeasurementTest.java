package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.SyncJobStatus;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.MockOtaProcess;
import com.staysync.channel.support.SyncTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * <b>완료 조건 16.</b> 재동기화 배치의 효과 측정. 계획서 6.6 이 정한 방식이다.
 *
 * <blockquote>Mock 시뮬레이터에 일정 에러율을 주입한 상태로 며칠간 운영해 불일치
 * 누적량을 측정한다. 배치 적용 전후의 누적 불일치 건수를 비교해 수렴 여부를 확인한다.
 * </blockquote>
 *
 * <p><b>불일치의 단위는 날짜다.</b> 우리 원장의 판매 가능 수량과 채널이 들고 있는 값이
 * 다른 날짜의 수를 센다. 작업 건수가 아니라 날짜 수인 이유는, 병합 버퍼가 연속 구간을
 * 압축하므로 작업 하나가 며칠을 덮는지가 그때그때 다르기 때문이다.
 *
 * <p>불일치를 만드는 손잡이는 둘이고, 12주차가 남긴 두 구멍에 각각 대응한다.
 *
 * <ol>
 *   <li><b>에러율 주입 + 운영 창 종료</b> — 백오프로 밀린 작업이 남은 채 그날이 끝난다.
 *       {@code RUNNING} 인 채 유실된 작업과 같은 모양의 잔여 불일치다</li>
 *   <li><b>채널이 받은 것을 잃어버림</b>({@code DELETE /api/ari}) — 받았다고 답해 놓고
 *       반영하지 않은 채널이다. <b>우리 쪽 로그는 전부 성공</b>이라 이 구멍은 다른
 *       어떤 장치로도 드러나지 않는다</li>
 * </ol>
 *
 * <p>측정값은 {@code docs/측정-03-재동기화.md} 에 그대로 옮겨 적는다.
 * <b>측정 전에 성과로 적지 않는다</b>(작업지시 10 의 2절 D).
 */
class ReconcileMeasurementTest extends SyncTestBase {

    /** 계획서 6.6 의 대조 구간. 측정도 같은 폭으로 한다. */
    private static final int HORIZON_DAYS = 180;

    /** 계획서 12.2 의 완료 조건 2가 쓰는 값보다 높게 잡는다. 잔여 불일치를 만들기 위해서다. */
    private static final String ERROR_RATE = "0.2";

    /** 시드 고정. 재현되지 않는 측정은 수치를 적을 수 없다(ADR 0011). */
    private static final String SEED = "20260905";

    /** "며칠간 운영"을 몇 번의 변경 묶음으로 본다. */
    private static final int ROUNDS = 12;

    /** 한 번의 운영 창에서 워커가 도는 횟수. 창이 닫히면 밀린 작업이 남는다. */
    private static final int CYCLES_PER_ROUND = 3;

    private static final short 판매수량 = 5;

    @Autowired
    private ChannelReconcileJob reconcile;

    private MockOtaProcess simulator;

    private Long fixtureUnitId;

    @BeforeEach
    void 시뮬레이터를_띄운다() {
        simulator = MockOtaProcess.start("stub-key",
                Map.of("error-rate", ERROR_RATE, "seed", SEED));
    }

    @AfterEach
    void 시뮬레이터를_내린다() {
        if (simulator != null) {
            simulator.close();
        }
    }

    @Test
    @DisplayName("에러율 20% 로 운영한 뒤 남은 불일치를 재동기화가 0 으로 수렴시킨다")
    void 재동기화_전후의_불일치를_측정한다() {
        Fixture fixture = given("측정", 판매수량);
        ChannelConnection connection = connect(fixture, "MOCK_MEASURE", AdapterType.MOCK,
                simulator.baseUrl(), "room-1");
        fixtureUnitId = fixture.unitId();

        LocalDate 오늘 = LocalDate.now();
        Random 시드된난수 = new Random(Long.parseLong(SEED));

        // --- 며칠간의 운영 ---------------------------------------------------
        // 첫 라운드는 전 구간을 채운다. 채널이 모르는 날짜는 무조건 차이로 잡히므로,
        // 그 상태에서 재면 "운영 중 생긴 불일치"가 아니라 "아직 안 보낸 날짜"를 센다.
        전구간을_보낸다(connection, 오늘);

        for (int round = 0; round < ROUNDS; round++) {
            // 우리 쪽 값을 실제로 바꾼다. 판매중지를 뒤집으면 원장이 바뀌고,
            // 그 변경이 채널에 닿지 못한 날짜가 곧 불일치가 된다.
            for (int i = 0; i <= HORIZON_DAYS; i++) {
                if (시드된난수.nextInt(4) != 0) {
                    // 매일 전 기간이 바뀌지는 않는다. 4분의 1만 건드린다.
                    continue;
                }
                LocalDate date = 오늘.plusDays(i);
                boolean 중지 = 시드된난수.nextBoolean();
                inventory.changeStopSell(fixtureUnitId, java.util.List.of(date), 중지);
                buffer.enqueue(connection.getId(), "room-1", "rate-1", date,
                        (int) 판매수량, BigDecimal.valueOf(150_000), 1, 중지);
            }
            buffer.flushAll();
            // 운영 창이 닫힌다. 백오프로 밀린 작업은 다음 창으로 넘어간다.
            worker.drainAll(CYCLES_PER_ROUND);
        }

        int 잔여작업 = (int) worker.countByStatus(connection.getId(), SyncJobStatus.PENDING);
        int 죽은작업 = (int) worker.countByStatus(connection.getId(), SyncJobStatus.DEAD);
        int 운영후_불일치 = 불일치일수(connection);

        // --- 채널이 받은 것을 잃어버린다 ----------------------------------------
        // 이 구멍은 우리 로그에 아무 흔적도 남기지 않는다. 대조만이 찾아낸다.
        simulator.forgetAri();
        int 유실후_불일치 = 불일치일수(connection);

        // --- 재동기화 배치 --------------------------------------------------
        int 배치가_찾은_차이 = reconcile.reconcileOne(connection);
        buffer.flushAll();
        // 배치가 만든 작업을 끝까지 보낸다. 백오프는 측정 대상이 아니므로 앞당긴다.
        for (int cycle = 0; cycle < 200; cycle++) {
            jdbc.update("UPDATE sync_job SET next_run_at = now() - interval '1 second' "
                    + "WHERE connection_id = ? AND status = 'PENDING'", connection.getId());
            if (worker.drainOnce() == 0) {
                break;
            }
        }
        int 배치후_불일치 = 불일치일수(connection);

        System.out.printf("""

                ===== 재동기화 효과 측정 (완료 조건 16) =====
                구간             %d일 (오늘 ~ +%d)
                주입 에러율      %s (시드 %s)
                운영 라운드      %d회, 라운드당 워커 %d주기
                ---------------------------------------------
                운영 후 남은 작업  PENDING %d건 / DEAD %d건
                운영 후 불일치     %d일
                채널 유실 후 불일치 %d일
                배치가 찾은 차이   %d일
                배치 후 불일치     %d일
                =============================================
                """,
                HORIZON_DAYS + 1, HORIZON_DAYS, ERROR_RATE, SEED, ROUNDS, CYCLES_PER_ROUND,
                잔여작업, 죽은작업, 운영후_불일치, 유실후_불일치, 배치가_찾은_차이, 배치후_불일치);

        assertThat(유실후_불일치)
                .as("채널이 받은 것을 잃어버리면 전 구간이 어긋난다. 우리 로그는 깨끗하다")
                .isEqualTo(HORIZON_DAYS + 1);
        assertThat(배치가_찾은_차이).isEqualTo(유실후_불일치);
        assertThat(배치후_불일치)
                .as("배치가 돌면 불일치가 0 으로 수렴해야 한다. 그게 이 배치의 존재 이유다")
                .isZero();
    }

    /** 전 구간을 한 번 보내 채널을 우리와 같은 상태로 만든다. */
    private void 전구간을_보낸다(ChannelConnection connection, LocalDate 오늘) {
        for (int i = 0; i <= HORIZON_DAYS; i++) {
            buffer.enqueue(connection.getId(), "room-1", "rate-1", 오늘.plusDays(i),
                    (int) 판매수량, BigDecimal.valueOf(150_000), 1, false);
        }
        buffer.flushAll();
        for (int cycle = 0; cycle < 100; cycle++) {
            jdbc.update("UPDATE sync_job SET next_run_at = now() - interval '1 second' "
                    + "WHERE connection_id = ? AND status = 'PENDING'", connection.getId());
            if (worker.drainOnce() == 0) {
                break;
            }
        }
    }

    /**
     * 지금 어긋난 날짜 수.
     *
     * <p>대조 자체를 재는 데 쓰므로 {@code reconcileOne} 을 부르되, 그 호출이 버퍼에
     * 넣은 것은 즉시 비운다 — 재는 행위가 상태를 바꾸면 다음 측정이 달라진다.
     */
    private int 불일치일수(ChannelConnection connection) {
        int drift = reconcile.reconcileOne(connection);
        버퍼를_비운다(connection);
        return drift;
    }

    /** 측정용 대조가 만든 작업을 지운다. 아직 보내지 않은 것만 지우므로 채널은 그대로다. */
    private void 버퍼를_비운다(ChannelConnection connection) {
        buffer.flushAll();
        jdbc.update("DELETE FROM sync_job WHERE connection_id = ? AND status = 'PENDING'",
                connection.getId());
    }
}
