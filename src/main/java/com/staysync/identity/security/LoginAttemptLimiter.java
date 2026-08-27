package com.staysync.identity.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 로그인 시도 제한. 계정(이메일) 기준으로 센다.
 *
 * <p>최근 {@link #WINDOW} 안에 {@link #MAX_FAILURES} 회 실패하면 {@link #LOCKOUT} 동안
 * 거절한다. 성공하면 카운터를 지운다.
 *
 * <p><b>존재하지 않는 이메일도 똑같이 센다.</b> 존재하는 계정에만 잠금이 걸리면 429
 * 응답 자체가 "그 이메일은 가입되어 있다"는 신호가 된다. 로그인 실패 응답을 하나로
 * 통일한 이유가 여기서 무너진다. 그래서 이 클래스는 계정의 존재 여부를 아예 모른다.
 *
 * <p>저장은 메모리다. 인스턴스가 하나뿐이고 재기동하면 초기화되는 것이 이 범위에서는
 * 문제가 되지 않는다. 인스턴스를 늘릴 때 분산 저장을 다시 본다.
 */
@Component
public class LoginAttemptLimiter {

    /** 이 횟수만큼 연속 실패하면 잠근다. */
    static final int MAX_FAILURES = 5;

    /** 실패를 세는 구간. 이 시간이 지나면 카운터가 리셋된다. */
    static final Duration WINDOW = Duration.ofMinutes(15);

    /** 잠금이 유지되는 시간. */
    static final Duration LOCKOUT = Duration.ofMinutes(10);

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    /** 지금 이 이메일의 로그인을 거절해야 하는지. */
    public boolean isLocked(String email, Instant now) {
        Attempts current = attempts.get(key(email));
        return current != null && current.isLockedAt(now);
    }

    public void recordFailure(String email, Instant now) {
        attempts.compute(key(email), (ignored, current) ->
                current == null || current.isStaleAt(now)
                        ? new Attempts(1, now, null)
                        : current.plusFailure(now));
        evictStale(now);
    }

    public void recordSuccess(String email) {
        attempts.remove(key(email));
    }

    /** 이메일 대소문자는 구분하지 않는다. 대문자로 바꿔가며 제한을 우회할 수 없어야 한다. */
    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /**
     * 만료된 항목을 비운다.
     *
     * <p>실패가 기록될 때만 돈다. 별도 스케줄러를 두지 않는 이유는 이 맵이 커지는 경로가
     * 로그인 실패뿐이라, 커지는 그 순간에 함께 줄이면 충분하기 때문이다.
     */
    private void evictStale(Instant now) {
        attempts.entrySet().removeIf(entry ->
                entry.getValue().isStaleAt(now) && !entry.getValue().isLockedAt(now));
    }

    /**
     * @param failures    구간 안의 연속 실패 수
     * @param firstFailedAt 구간의 시작
     * @param lockedUntil 잠금 해제 시각. 잠기지 않았으면 null
     */
    private record Attempts(int failures, Instant firstFailedAt, Instant lockedUntil) {

        Attempts plusFailure(Instant now) {
            int next = failures + 1;
            return new Attempts(
                    next,
                    firstFailedAt,
                    next >= MAX_FAILURES ? now.plus(LOCKOUT) : lockedUntil);
        }

        boolean isLockedAt(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }

        /** 세는 구간을 벗어났는지. 벗어난 카운터는 처음부터 다시 센다. */
        boolean isStaleAt(Instant now) {
            return firstFailedAt.plus(WINDOW).isBefore(now);
        }
    }
}
