package com.staysync.channel;

import com.staysync.channel.domain.SyncJob;
import com.staysync.channel.domain.SyncJobStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code sync_job} 을 주기적으로 집어 채널로 보낸다. 계획서 13.3 이 명세다.
 *
 * <p>이 클래스는 <b>주기와 순회만</b> 맡고, 실제 클레임·전송·결과 기록은
 * {@link SyncJobRunner} 가 트랜잭션 안에서 한다. 같은 클래스 안에서
 * {@code @Transactional} 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다.
 *
 * <p><b>같은 연결의 작업은 순서대로 처리된다.</b> 그 보장은 여기가 아니라
 * {@link SyncJobRepository#claimBatch} 의 {@code NOT EXISTS} 가 만든다 — 연결당 맨 앞
 * 한 건만 나오므로 이 루프가 따로 묶어 직렬화할 필요가 없다. 계획서 13.3 의
 * {@code groupingBy} + {@code parallelStream} 을 쓰지 않은 이유다.
 *
 * <p>ShedLock 을 쓰지 않는다. 인스턴스가 하나뿐이라 지금 막을 것이 없고,
 * {@code SKIP LOCKED} 가 앱을 두 번 띄우는 실수까지는 이미 막는다. 여러 대가 되면
 * 그때 넣는다({@code OutboxRelay} 와 같은 자리다).
 *
 * <p>재시도 정책의 근거는 docs/adr/0012-채널-재시도-정책.md.
 */
@Component
public class SyncJobWorker {

    private static final Logger log = LoggerFactory.getLogger(SyncJobWorker.class);

    /** 한 번에 집을 작업 수. 연결당 한 건씩이라 사실상 "동시에 다룰 연결 수"다. */
    static final int BATCH_LIMIT = 20;

    /** 재시도 상한. 계획서 13.3 의 값이다. */
    static final int MAX_ATTEMPT = 8;

    /** 지수 백오프 상한. 30분을 넘겨 기다릴 만큼 급하지 않은 변경은 없다. */
    static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    /** 채널이 알려 주지 않을 때 쓰는 한도 대기 시간. */
    static final Duration RATE_LIMIT_PAUSE = Duration.ofSeconds(60);

    /**
     * 이만큼 {@code RUNNING} 으로 남아 있으면 고아로 본다.
     *
     * <p>가장 긴 정상 전송보다 넉넉히 길어야 한다. 어댑터 타임아웃이 10초이고
     * 트랜잭션이 그 바깥이라, 5분이면 살아 있는 작업을 뺏을 일이 없다.
     */
    static final Duration ORPHAN_AFTER = Duration.ofMinutes(5);

    private final SyncJobRunner runner;
    private final SyncJobRepository jobs;
    private final Duration orphanAfter;

    SyncJobWorker(SyncJobRunner runner, SyncJobRepository jobs,
                  @org.springframework.beans.factory.annotation.Value(
                          "${staysync.channel.orphan-after-ms:300000}") long orphanAfterMs) {
        this.runner = runner;
        this.jobs = jobs;
        this.orphanAfter = orphanAfterMs > 0 ? Duration.ofMillis(orphanAfterMs) : ORPHAN_AFTER;
    }

    @Scheduled(fixedDelayString = "${staysync.channel.worker-interval-ms:1000}",
            initialDelayString = "${staysync.channel.worker-interval-ms:1000}")
    public void run() {
        drainOnce();
    }

    /**
     * 한 주기 분량을 집어 처리한다. 테스트가 스케줄러를 기다리지 않고 부른다.
     *
     * @return 처리한 건수(성공·실패 무관)
     */
    public int drainOnce() {
        // 집기 전에 되살린다. 순서가 반대면 되살아난 작업이 다음 주기까지 기다린다.
        reviveOrphans();
        List<SyncJob> claimed = runner.claim(BATCH_LIMIT);
        for (SyncJob job : claimed) {
            runner.execute(job.getId());
        }
        return claimed.size();
    }

    /**
     * 더 집을 것이 없을 때까지 돌린다. 테스트와 재동기화가 쓴다.
     *
     * <p>연결당 한 건씩 나오므로, 한 연결에 밀린 작업 다섯 건을 다 보내려면 다섯 번
     * 돌아야 한다. 상한을 두는 것은 재시도로 다시 {@code PENDING} 이 된 작업이
     * {@code next_run_at} 때문에 곧바로는 안 나오는데도 무한히 도는 일을 막기 위해서다.
     */
    public int drainAll(int maxCycles) {
        int total = 0;
        for (int cycle = 0; cycle < maxCycles; cycle++) {
            int handled = drainOnce();
            if (handled == 0) {
                break;
            }
            total += handled;
        }
        return total;
    }

    /**
     * <b>고아 작업을 되살린다.</b> {@code RUNNING} 인 채 앱이 죽어 유실된 작업이다.
     *
     * <p>이게 없으면 그 연결이 <b>영영 막힌다</b> — 클레임의 {@code NOT EXISTS} 가
     * 같은 연결의 앞선 작업을 기다리기 때문이다. 12주차가 순서 보장을 얻으면서 같이
     * 만든 구멍이고, 13주차의 재동기화 배치가 결국 덮지만 하루를 기다리는 것과 몇
     * 분은 다르다.
     *
     * <p>여기에 둔 이유는 <b>{@code sync_job} 의 상태를 워커 밖에서 바꾸지 않는다</b>는
     * 12주차 규칙이다. 클레임과 완료가 한곳에 있어야 {@code SKIP LOCKED} 가 의미를 갖고,
     * 되살리기도 상태 변경이다.
     *
     * @return 되살린 건수
     */
    public int reviveOrphans() {
        int revived = runner.reviveOrphans(OffsetDateTime.now().minus(orphanAfter));
        if (revived > 0) {
            log.warn("워커가 집은 뒤 끝내지 못한 작업 {}건을 되살렸다. 앱이 죽었을 수 있다", revived);
        }
        return revived;
    }

    /** 테스트가 상태를 확인할 때 쓴다. */
    public List<SyncJob> jobsOf(Long connectionId) {
        return jobs.findByConnectionIdOrderByIdAsc(connectionId);
    }

    public long countByStatus(Long connectionId, SyncJobStatus status) {
        return jobsOf(connectionId).stream().filter(job -> job.getStatus() == status).count();
    }
}
