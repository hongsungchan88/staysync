package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.SyncJob;
import com.staysync.channel.domain.SyncJobStatus;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.ChannelStubServer;
import com.staysync.channel.support.SyncTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 완료 조건 5·6·7. 동기화 워커의 재시도 정책과 순서 보장.
 *
 * <p>어댑터는 진짜를 쓰고 채널만 스텁으로 세운다. 상태 코드를 세 갈래로 옮기는 것이
 * {@code MockOtaAdapter} 의 핵심이라, 거기를 우회하면 검증할 것이 사라진다.
 *
 * <p>근거는 docs/adr/0012-채널-재시도-정책.md.
 */
class SyncJobWorkerTest extends SyncTestBase {

    private static final LocalDate 첫날 = LocalDate.of(2027, 4, 1);

    private ChannelStubServer channel;

    @BeforeEach
    void 채널을_세운다() {
        channel = new ChannelStubServer();
    }

    @AfterEach
    void 채널을_내린다() {
        channel.close();
    }

    @Test
    @DisplayName("정상이면 SUCCESS 가 되고 자격 증명이 실려 나간다")
    void 성공하면_자격_증명이_실린다() {
        ChannelConnection connection = mockConnection("워커-성공");
        enqueueOneDay(connection, BigDecimal.valueOf(250_000));

        worker.drainOnce();

        assertThat(status(connection)).containsExactly(SyncJobStatus.SUCCESS);
        // 자격 증명을 빠뜨리면 실제 OTA 로 바꾸는 순간 전부 401 이 된다. 시뮬레이터가
        // /api/** 전체에서 키를 검사하는 이유이기도 하다.
        assertThat(channel.apiKeys()).containsExactly("stub-key");
    }

    @Test
    @DisplayName("429 면 60초 뒤로 미뤄지고 시도 횟수만 오른다")
    void 한도에_걸리면_60초_뒤다() {
        ChannelConnection connection = mockConnection("워커-429");
        enqueueOneDay(connection, BigDecimal.valueOf(250_000));

        OffsetDateTime before = OffsetDateTime.now();
        channel.alwaysRespond(429);
        worker.drainOnce();

        SyncJob job = only(connection);
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.PENDING);
        // 한도는 실패가 아니라 속도 조절이다. 지수 백오프로 물러나면 밀린 작업이
        // 계속 밀린다.
        assertThat(job.getNextRunAt()).isAfter(before.plusSeconds(50));
        assertThat(job.getNextRunAt()).isBefore(before.plusSeconds(70));
    }

    @Test
    @DisplayName("5xx 면 지수 백오프로 물러난다")
    void 일시_오류는_지수적으로_물러난다() {
        ChannelConnection connection = mockConnection("워커-5xx");
        enqueueOneDay(connection, BigDecimal.valueOf(250_000));

        channel.alwaysRespond(503);
        worker.drainOnce();
        SyncJob 첫실패 = only(connection);

        assertThat(첫실패.getStatus()).isEqualTo(SyncJobStatus.PENDING);
        assertThat(첫실패.getAttempt()).isEqualTo((short) 1);
        assertThat(첫실패.getLastError()).isNotNull();

        // 2^attempt 초. 1회 시도 뒤면 2초다.
        assertThat(SyncJobRunner.backoffFor(1)).isEqualTo(java.time.Duration.ofSeconds(2));
        assertThat(SyncJobRunner.backoffFor(5)).isEqualTo(java.time.Duration.ofSeconds(32));
        // 상한 30분. 그보다 오래 기다릴 만큼 급하지 않은 변경은 없다.
        assertThat(SyncJobRunner.backoffFor(20)).isEqualTo(java.time.Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("영구 오류는 재시도하지 않고 바로 DEAD 다")
    void 영구_오류는_즉시_포기한다() {
        ChannelConnection connection = mockConnection("워커-영구");
        enqueueOneDay(connection, BigDecimal.valueOf(250_000));

        // 401 은 키가 틀린 것이다. 여덟 번 두드려도 답이 같다.
        channel.alwaysRespond(401);
        worker.drainOnce();

        SyncJob job = only(connection);
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.DEAD);
        assertThat(job.getAttempt()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("8회를 넘기면 DEAD 가 되고 행은 남는다")
    void 상한에_닿으면_죽되_행은_남는다() {
        ChannelConnection connection = mockConnection("워커-상한");
        enqueueOneDay(connection, BigDecimal.valueOf(250_000));
        channel.alwaysRespond(503);

        // next_run_at 이 미래로 밀리므로 그냥 돌리면 다시 집히지 않는다. 시간을
        // 앞당겨 아홉 번 시도시킨다.
        for (int i = 0; i < 9; i++) {
             jdbc.update("UPDATE sync_job SET next_run_at = now() - interval '1 second' "
                    + "WHERE connection_id = ? AND status = 'PENDING'", connection.getId());
            worker.drainOnce();
        }

        SyncJob job = only(connection);
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.DEAD);
        // 행을 지우지 않는다. last_error 가 남아 있어야 사람이 원인을 보고 다시 넣는다.
        assertThat(job.getLastError()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_job WHERE connection_id = ?",
                Integer.class, connection.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 연결의 작업은 앞엣것이 끝나야 뒤엣것이 나간다")
    void 같은_연결은_순서대로_처리된다() {
        ChannelConnection connection = mockConnection("워커-순서");
        enqueueOneDay(connection, BigDecimal.valueOf(250_000));
        enqueueOneDay(connection, BigDecimal.valueOf(230_000));
        assertThat(worker.jobsOf(connection.getId())).hasSize(2);

        // 첫 작업이 실패해 60초 뒤로 밀린다.
        channel.alwaysRespond(503);
        worker.drainOnce();

        // 이때 둘째가 나가면, 60초 뒤 첫째의 재시도가 **옛 값으로 새 값을 덮어쓴다.**
        // 채널 쪽 요금이 틀리는데 우리 로그는 전부 성공이라 아무 증상이 없다.
        channel.reset();
        channel.alwaysRespond(202);
        worker.drainOnce();

        assertThat(channel.callCount())
                .as("앞엣것이 아직 대기 중이면 뒤엣것은 나가지 않아야 한다")
                .isZero();
        assertThat(status(connection)).containsExactly(SyncJobStatus.PENDING, SyncJobStatus.PENDING);
    }

    @Test
    @DisplayName("앞엣것이 성공하면 뒤엣것이 이어서 나간다")
    void 앞이_끝나면_뒤가_나간다() {
        ChannelConnection connection = mockConnection("워커-순차");
        enqueueOneDay(connection, BigDecimal.valueOf(250_000));
        enqueueOneDay(connection, BigDecimal.valueOf(230_000));

        worker.drainOnce();
        worker.drainOnce();

        assertThat(status(connection)).containsExactly(SyncJobStatus.SUCCESS, SyncJobStatus.SUCCESS);
        // 보낸 순서가 만든 순서와 같아야 채널의 최종 값이 우리 최종 값과 같다.
        assertThat(channel.bodies().get(0)).contains("250000");
        assertThat(channel.bodies().get(1)).contains("230000");
    }

    @Test
    @DisplayName("다른 연결은 서로를 막지 않는다")
    void 다른_연결은_막히지_않는다() {
        Fixture fixture = given("워커-병렬");
        ChannelConnection 막힌쪽 = connect(fixture, "MOCK_A", AdapterType.MOCK,
                channel.baseUrl(), "room-a");
        ChannelConnection 나가는쪽 = connect(fixture, "MOCK_B", AdapterType.MOCK,
                channel.baseUrl(), "room-b");

        // 먼저 A 만 막아 둔다. 스텁은 응답을 하나로만 짜므로 B 의 작업은 아직 만들지 않는다.
        enqueueOneDay(막힌쪽, BigDecimal.valueOf(250_000));
        enqueueOneDay(막힌쪽, BigDecimal.valueOf(230_000));
        channel.alwaysRespond(503);
        worker.drainOnce();
        assertThat(status(막힌쪽)).containsExactly(SyncJobStatus.PENDING, SyncJobStatus.PENDING);

        // 이제 B 의 작업을 넣고 채널을 정상으로 돌린다. A 는 여전히 백오프 중이다.
        enqueueOneDay(나가는쪽, BigDecimal.valueOf(300_000));
        channel.alwaysRespond(202);
        worker.drainOnce();

        // 한 채널의 장애가 다른 채널의 갱신을 막으면 안 된다. 순서 보장은 연결
        // 안에서만이다.
        assertThat(status(나가는쪽)).containsExactly(SyncJobStatus.SUCCESS);
        assertThat(status(막힌쪽)).containsExactly(SyncJobStatus.PENDING, SyncJobStatus.PENDING);
    }

    // --- 픽스처 ---------------------------------------------------------------

    private ChannelConnection mockConnection(String name) {
        return connect(given(name), "MOCK_" + name, AdapterType.MOCK,
                channel.baseUrl(), "room-1");
    }

    /** 버퍼를 거쳐 작업 하나를 만든다. 채널마다 다른 경로를 만들지 않는다는 규칙 그대로다. */
    private void enqueueOneDay(ChannelConnection connection, BigDecimal rate) {
        buffer.enqueue(connection.getId(), "room-" + connection.getId(), "rate-1", 첫날,
                null, rate, 1, null);
        buffer.flushAll();
    }

    private SyncJob only(ChannelConnection connection) {
        List<SyncJob> jobs = worker.jobsOf(connection.getId());
        assertThat(jobs).hasSize(1);
        return jobs.get(0);
    }

    private List<SyncJobStatus> status(ChannelConnection connection) {
        return worker.jobsOf(connection.getId()).stream().map(SyncJob::getStatus).toList();
    }
}
