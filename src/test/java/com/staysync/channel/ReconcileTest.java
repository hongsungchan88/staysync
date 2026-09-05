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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * <b>완료 조건 12·13·16.</b> 정기 재동기화와 고아 작업 되살리기.
 *
 * <p>계획서 6.6 이 명세다. 이 배치가 12주차의 두 구멍을 덮는다 — {@code RUNNING} 인
 * 채 앱이 죽어 유실된 작업과, 메모리 버퍼에 있다가 사라진 6초치.
 *
 * <p>시뮬레이터를 <b>별도 프로세스로 실제로 띄운다.</b> 여기서 재려는 것이 "채널이
 * 받았다고 답해 놓고 반영하지 않았다"이고, 그건 실제 왕복이 있어야 만들어진다.
 */
class ReconcileTest extends SyncTestBase {

    private static final LocalDate 첫날 = LocalDate.now().plusDays(3);

    /** {@code staysync.channel.reconcile-horizon-days} 의 기본값. 계획서 6.6 의 180일이다. */
    private static final int HORIZON_DAYS = 180;

    /**
     * 판매 수량. <b>채널에 보내는 재고와 같아야 한다.</b>
     *
     * <p>대조는 우리 원장의 판매 가능 수량과 채널이 들고 있는 값을 비교한다. 예약이
     * 없는 날의 우리 값은 곧 {@code totalUnits} 이므로, 다른 수를 보내 놓고 "차이가
     * 없어야 한다"를 기대하면 전 기간이 차이로 잡힌다.
     */
    private static final short 판매수량 = 3;

    @Autowired
    private ChannelReconcileJob reconcile;

    private MockOtaProcess simulator;

    @BeforeEach
    void 시뮬레이터를_띄운다() {
        simulator = MockOtaProcess.start("stub-key", Map.of("error-rate", "0", "seed", "20260905"));
    }

    @AfterEach
    void 시뮬레이터를_내린다() {
        if (simulator != null) {
            simulator.close();
        }
    }

    @Test
    @DisplayName("우리 값과 채널 값이 다르면 차이를 찾아 다시 보낸다")
    void 채널이_잃어버린_값을_다시_보낸다() {
        Fixture fixture = given("재동기화", 판매수량);
        ChannelConnection connection = connect(fixture, "MOCK_RECONCILE", AdapterType.MOCK,
                simulator.baseUrl(), "room-1");

        보내고_비운다(connection);

        // 채널이 받은 것을 잃어버렸다. 우리 쪽 로그는 전부 성공이고 아무 증상이 없다.
        simulator.forgetAri();

        int drift = reconcile.reconcileOne(connection);

        assertThat(drift).as("대조가 차이를 찾아야 한다").isPositive();
        buffer.flushAll();
        assertThat(worker.jobsOf(connection.getId()))
                .as("차이를 다시 보내는 작업이 만들어져야 한다")
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("채널 값이 우리 값과 같으면 다시 보내지 않는다")
    void 같으면_아무것도_하지_않는다() {
        Fixture fixture = given("재동기화 무변화", 판매수량);
        ChannelConnection connection = connect(fixture, "MOCK_NO_DRIFT", AdapterType.MOCK,
                simulator.baseUrl(), "room-1");

        보내고_비운다(connection);

        // 여기서 차이가 나오면 매일 새벽 4시에 전 기간을 다시 보내게 된다.
        // 한도를 그대로 넘기고, 그 상태가 조용히 계속된다.
        assertThat(reconcile.reconcileOne(connection)).isZero();
    }

    @Test
    @DisplayName("RUNNING 인 채 오래 남은 작업이 PENDING 으로 돌아온다")
    void 고아_작업이_되살아난다() {
        Fixture fixture = given("고아");
        ChannelConnection connection = connect(fixture, "MOCK_ORPHAN", AdapterType.MOCK,
                simulator.baseUrl(), "room-1");

        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날,
                3, BigDecimal.valueOf(120_000), 1, null);
        buffer.flushAll();
        Long jobId = worker.jobsOf(connection.getId()).get(0).getId();

        // 워커가 집은 뒤 앱이 죽은 상태를 만든다. next_run_at 이 집은 시각이다.
        jdbc.update("UPDATE sync_job SET status = 'RUNNING', "
                + "next_run_at = now() - interval '1 hour' WHERE id = ?", jobId);

        // 이 상태로 두면 클레임의 NOT EXISTS 가 이 연결 전체를 영영 막는다.
        //
        // 되살리기는 연결을 가리지 않으므로 반환값에는 다른 테스트가 남긴 행도 섞인다.
        // 세는 대신 이 연결의 그 작업이 실제로 돌아왔는지를 본다.
        assertThat(worker.reviveOrphans()).isPositive();
        assertThat(worker.jobsOf(connection.getId()).get(0).getStatus())
                .isEqualTo(SyncJobStatus.PENDING);
        assertThat(worker.jobsOf(connection.getId()).get(0).getAttempt())
                .as("시도조차 못 한 것이라 attempt 를 올리지 않는다")
                .isZero();

        // 되살아났으니 실제로 나간다.
        worker.drainAll(5);
        assertThat(worker.countByStatus(connection.getId(), SyncJobStatus.SUCCESS)).isEqualTo(1);
    }

    @Test
    @DisplayName("방금 집은 작업은 되살리지 않는다")
    void 살아_있는_작업을_뺏지_않는다() {
        Fixture fixture = given("살아있는 작업");
        ChannelConnection connection = connect(fixture, "MOCK_ALIVE", AdapterType.MOCK,
                simulator.baseUrl(), "room-1");

        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날, 3, null, null, null);
        buffer.flushAll();
        jdbc.update("UPDATE sync_job SET status = 'RUNNING', next_run_at = now() "
                + "WHERE connection_id = ?", connection.getId());

        // 되살리면 같은 전송이 두 번 나간다. 지금 돌고 있는 작업을 뺏는 것이다.
        // 반환값에는 다른 테스트의 행이 섞이므로 이 연결의 상태를 직접 본다.
        worker.reviveOrphans();
        assertThat(worker.jobsOf(connection.getId()).get(0).getStatus())
                .isEqualTo(SyncJobStatus.RUNNING);
    }

    @Test
    @DisplayName("iCal 연결은 대조하지 않는다. 보낼 수가 없어 대조할 것도 없다")
    void iCal_은_재동기화_대상이_아니다() {
        Fixture fixture = given("iCal 제외");
        ChannelConnection ical = channels.create(
                fixture.propertyId(), fixture.orgId(), "AIRBNB_ICAL_RECONCILE",
                AdapterType.ICAL, "에어비앤비",
                Map.of("ical_url", "https://example.com/x.ics"));
        channels.addMapping(ical.getId(), fixture.orgId(), fixture.unitId(), "listing-1", null);

        // fetchAriSnapshot 이 UnsupportedOperationException 을 던지므로, 걸러지지
        // 않으면 여기서 예외가 로그에 남고 drift 가 0 이 된다. 걸러야 맞다.
        assertThat(reconcile.reconcileOne(ical)).isZero();
        buffer.flushAll();
        assertThat(worker.jobsOf(ical.getId())).isEmpty();
    }

    /**
     * 대조 구간 전체를 보내고 실제로 채널에 반영시킨다.
     *
     * <p><b>구간을 다 채워야 한다.</b> 채널이 모르는 날짜는 대조가 차이로 보기
     * 때문이다(우리가 보낸 적이 있는데 채널에 없으면 유실이다). 열흘만 보내고
     * "차이가 없어야 한다"를 기대하면 나머지 171일이 전부 차이로 잡힌다.
     *
     * <p>값이 전부 같아 버퍼가 한 구간으로 압축하므로 작업은 한 건이다. 그게
     * 병합 버퍼를 거치는 이유이기도 하다 — 날짜마다 보내면 분당 한도를 그대로 넘긴다.
     */
    private void 보내고_비운다(ChannelConnection connection) {
        LocalDate 오늘 = LocalDate.now();
        for (int i = 0; i <= HORIZON_DAYS; i++) {
            buffer.enqueue(connection.getId(), "room-1", "rate-1", 오늘.plusDays(i),
                    (int) 판매수량, BigDecimal.valueOf(150_000), 1, false);
        }
        buffer.flushAll();
        worker.drainAll(20);
        assertThat(worker.countByStatus(connection.getId(), SyncJobStatus.SUCCESS)).isPositive();
    }
}
