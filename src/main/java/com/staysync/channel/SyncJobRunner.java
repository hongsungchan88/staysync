package com.staysync.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.staysync.channel.domain.SyncJob;
import com.staysync.channel.port.AriUpdateCommand;
import com.staysync.channel.port.ChannelAdapter;
import com.staysync.channel.port.ChannelCredentials;
import com.staysync.channel.port.ChannelException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SyncJobWorker} 의 <b>트랜잭션 경계</b>. 실제로 집고 보내고 결과를 적는다.
 *
 * <p>둘을 나눈 이유는 이 프로젝트에서 반복된 그것이다 — 같은 클래스 안에서
 * {@code @Transactional} 메서드를 부르면 스프링 프록시를 거치지 않아 트랜잭션이 아예
 * 걸리지 않는다. {@code BookingService}/{@code ReservationWriter},
 * {@code InventoryService}/{@code InventoryLedgerWriter} 와 같은 나눔이다.
 *
 * <p><b>클레임과 전송이 서로 다른 트랜잭션</b>인 것도 의도다. 한 트랜잭션에 두면 HTTP
 * 왕복 내내 트랜잭션이 열려 있어 커넥션 풀이 마른다. 지연이 주입된 채널이 있으면 더
 * 그렇다.
 */
@Component
class SyncJobRunner {

    private static final Logger log = LoggerFactory.getLogger(SyncJobRunner.class);

    private final SyncJobRepository jobs;
    private final ChannelConnectionRepository connections;
    private final ChannelAdapterRegistry registry;
    private final ChannelCredentialStore credentials;
    private final ObjectMapper json;

    SyncJobRunner(SyncJobRepository jobs,
                  ChannelConnectionRepository connections,
                  ChannelAdapterRegistry registry,
                  ChannelCredentialStore credentials,
                  ObjectMapper json) {
        this.jobs = jobs;
        this.connections = connections;
        this.registry = registry;
        this.credentials = credentials;
        this.json = json;
    }

    /**
     * 돌릴 작업을 집는다. 연결당 맨 앞 한 건씩만 나온다.
     *
     * <p>{@code RETURNING *} 이라 네이티브 갱신 한 문장으로 끝난다. 집는 것과
     * {@code attempt} 를 올리는 것이 한 문장이어야 워커가 중간에 죽어도 횟수가 새지 않는다.
     */
    @Transactional
    List<SyncJob> claim(int limit) {
        return jobs.claimBatch(limit);
    }

    /** 고아 되살리기의 트랜잭션 경계. 이유는 {@link SyncJobWorker#reviveOrphans} 에 있다. */
    @Transactional
    int reviveOrphans(java.time.OffsetDateTime staleBefore) {
        return jobs.reviveOrphans(staleBefore);
    }

    /**
     * 한 건을 보내고 결과를 적는다.
     *
     * <p>작업을 다시 읽는다. 클레임 트랜잭션이 이미 끝나 앞의 엔티티는 준영속이고,
     * 준영속 객체의 상태를 바꿔 봐야 저장되지 않는다.
     */
    @Transactional
    void execute(Long jobId) {
        SyncJob job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        try {
            send(job);
            job.succeed();
        } catch (ChannelException.RateLimitedException e) {
            // 실패가 아니라 속도 조절이다. 백오프를 올리지 않는다 — 한도는 시간이
            // 지나면 풀리고, 여기서 지수적으로 물러나면 밀린 작업이 계속 밀린다.
            job.retryAfter(orDefault(e.retryAfter()), e.getMessage());
            log.warn("채널 요청 한도에 걸렸다. jobId={} connectionId={} 다시={}",
                    job.getId(), job.getConnectionId(), job.getNextRunAt());
        } catch (ChannelException.PermanentChannelException e) {
            job.markDead(e.getMessage());
            log.error("채널이 영구 오류로 답해 작업을 포기한다. jobId={} connectionId={} 사유={}",
                    job.getId(), job.getConnectionId(), e.getMessage());
        } catch (RuntimeException e) {
            retryOrDie(job, e);
        }
    }

    /**
     * 일시 오류. 지수 백오프로 물러나되 상한에 닿으면 포기한다.
     *
     * <p>{@code ChannelException} 이 아닌 예외도 여기로 온다. 어댑터의 버그를 영구
     * 오류로 단정하면 고친 뒤에도 작업이 죽어 있고, 일시 오류로 두면 8번 안에 드러난다.
     */
    private void retryOrDie(SyncJob job, RuntimeException e) {
        if (job.getAttempt() >= SyncJobWorker.MAX_ATTEMPT) {
            job.markDead(e.toString());
            // 상한에 닿는 그 한 번만 남긴다. DEAD 행은 지우지 않으므로 사람이 last_error
            // 를 보고 원인을 고친 뒤 다시 넣을 수 있다.
            log.error("채널 전송이 재시도 상한 {}회에 닿아 더 시도하지 않는다. "
                            + "jobId={} connectionId={} 원인을 확인해야 한다",
                    SyncJobWorker.MAX_ATTEMPT, job.getId(), job.getConnectionId(), e);
            return;
        }
        job.retryAfter(backoffFor(job.getAttempt()), e.toString());
        log.warn("채널 전송에 실패해 다시 시도한다. jobId={} 시도={}회 다음={}",
                job.getId(), job.getAttempt(), job.getNextRunAt());
    }

    /** {@code 2^attempt} 초, 상한 30분. 계획서 13.3 의 {@code 1 << attempt} 와 같다. */
    static Duration backoffFor(int attempt) {
        Duration backoff = Duration.ofSeconds(1L << Math.min(attempt, 16));
        return backoff.compareTo(SyncJobWorker.MAX_BACKOFF) > 0
                ? SyncJobWorker.MAX_BACKOFF : backoff;
    }

    private static Duration orDefault(Duration retryAfter) {
        return retryAfter == null ? SyncJobWorker.RATE_LIMIT_PAUSE : retryAfter;
    }

    private void send(SyncJob job) {
        var connection = connections.findById(job.getConnectionId())
                .orElseThrow(() -> new ChannelException.PermanentChannelException(
                        "채널 연결이 없습니다. connectionId=" + job.getConnectionId()));

        if (!connection.isSyncEnabled()) {
            // 사용자가 연결을 껐다. 다시 켤 때까지 재시도해 봐야 소용없다.
            throw new ChannelException.PermanentChannelException(
                    "동기화가 꺼진 연결입니다. connectionId=" + connection.getId());
        }

        ChannelAdapter adapter = registry.get(connection.getAdapterType());
        ChannelCredentials creds = new ChannelCredentials(
                connection.getId(), connection.getChannelCode(),
                credentials.reveal(connection.getCredentials()));

        switch (job.getJobType()) {
            case PUSH_ARI -> adapter.pushAri(creds, parseAri(job));
            // 수신은 폴링이 직접 돌린다(작업지시 09 의 5절 1번). 작업으로 만들지 않았다.
            case PULL_BOOKING, PULL_ICAL -> throw new ChannelException.PermanentChannelException(
                    "이 작업 종류는 아직 워커가 다루지 않습니다: " + job.getJobType());
        }
    }

    private AriUpdateCommand parseAri(SyncJob job) {
        try {
            return json.readValue(job.getPayload(), AriUpdateCommand.class);
        } catch (Exception e) {
            // 페이로드가 깨졌다. 다시 보내도 같은 결과다.
            throw new ChannelException.PermanentChannelException(
                    "ARI 페이로드를 읽지 못했습니다: " + e.getMessage());
        }
    }
}
