package com.staysync.channel.adapter.channex;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 우리 쪽 전송 상한 — Channex 문서 값, <b>숙소당 분당 요금·제약 10회, 재고 10회</b>.
 *
 * <p>스테이징의 {@code ratelimit-policy} 헤더는 분당 6,000 이라 429 를 본 적이 없다.
 * 그래도 문서 값을 지킨다 — 운영은 더 엄격할 수 있고 상용 인증 기준도 문서 쪽이다
 * (작업지시-17 8.3). 넘으면 채널에 닿기 전에 {@code RateLimitedException} 으로 돌려
 * 워커가 창이 열릴 때까지 그 연결의 작업을 미룬다. 6초 병합 버퍼가 평소에 10/분 아래로
 * 누르지만, 판매 단위가 여럿인 숙소는 단위마다 작업이 생겨 넘길 수 있다.
 *
 * <p>ponytail: 메모리 고정 창, 인스턴스 하나 전제(공개 HOLD 제한과 같은 모양). 서버를
 * 늘리면 Redis 카운터로.
 */
class ChannexSendLimiter {

    static final int MAX_PER_WINDOW = 10;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * @param key 숙소 식별자 + 끝점("{property}:availability" 또는 ":restrictions")
     * @return 상한 안이면 {@code null}, 넘으면 창이 다시 열릴 때까지 기다릴 시간
     */
    Duration tryAcquire(String key, Instant now) {
        Window current = windows.compute(key, (ignored, existing) ->
                existing == null || !existing.startedAt.plus(WINDOW).isAfter(now)
                        ? new Window(now, 1)
                        : new Window(existing.startedAt, existing.count + 1));
        if (current.count <= MAX_PER_WINDOW) {
            return null;
        }
        Duration wait = Duration.between(now, current.startedAt.plus(WINDOW));
        return wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait;
    }

    private record Window(Instant startedAt, int count) {
    }
}
