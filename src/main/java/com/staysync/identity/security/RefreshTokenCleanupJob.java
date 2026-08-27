package com.staysync.identity.security;

import com.staysync.identity.RefreshTokenRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 리프레시 토큰을 지운다. 하루 한 번 돈다.
 *
 * <p>만료 즉시가 아니라 {@link #GRACE} 만큼 지난 것만 지운다. 행이 사라지면 "만료된
 * 토큰이 제시됐다"와 "존재한 적 없는 토큰이 제시됐다"를 구분할 수 없다. 앞은 흔한
 * 일이고 뒤는 공격 신호라 로그에서 갈라 보고 싶다. 근거는 ADR 0005.
 *
 * <p>인스턴스가 하나뿐이라 ShedLock 을 걸지 않았다. 여러 대가 되면 배치가 중복 실행되므로
 * 그때 건다. {@code shedlock} 테이블과 의존성 주석은 이미 준비돼 있다.
 */
@Component
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    /** 만료 후 이만큼 지난 행부터 지운다. */
    static final Duration GRACE = Duration.ofDays(7);

    private final RefreshTokenRepository repository;

    public RefreshTokenCleanupJob(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    /** 매일 새벽 4시. 사용이 가장 적은 시간대다. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void run() {
        deleteExpired(OffsetDateTime.now());
    }

    /**
     * 스케줄러를 기다리지 않고 부를 수 있게 분리했다. 테스트가 이 메서드를 쓴다.
     *
     * @return 지운 행 수
     */
    @Transactional
    public int deleteExpired(OffsetDateTime now) {
        int deleted = repository.deleteExpiredBefore(now.minus(GRACE));
        if (deleted > 0) {
            log.info("만료된 리프레시 토큰 {}건을 정리했다", deleted);
        }
        return deleted;
    }
}
