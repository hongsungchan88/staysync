package com.staysync.shared.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 클라이언트 IP 당 고정 시간 창 안의 요청 수를 세는 속도 제한(작업지시-18 D 에서 시작).
 *
 * <p>로그인 없이 열린 경로가 쓴다 — 공개 HOLD 생성과 가입. 둘 다 반복하면 남에게 비용이
 * 가는 쓰기다(재고를 비워 두기, 조직·계정을 무한히 만들기). 이 클래스는 그 반복을 IP
 * 하나 안에서 막는 얇은 한 겹이다. <b>여러 IP 로 나눠 오는 공격은 막지 못한다.</b>
 *
 * <p>성공·실패를 가리지 않고 <b>요청 자체</b>를 센다. {@code LoginAttemptLimiter} 는
 * 실패를 세어 잠그는 장치라 성격이 다르고, identity 내부 타입이라 다른 모듈이 못 쓴다.
 *
 * <p>경로마다 창과 상한이 다르므로 빈이 아니다. 경로별 {@code @Component} 가 상속해
 * 설정 값을 넣는다({@code PublicHoldRateLimiter}, {@code SignupRateLimiter}). 판정은
 * 락·트랜잭션 밖(컨트롤러)에서 하고, 넘으면 아무것도 만들지 않고 429 다.
 *
 * <p>ponytail: 메모리 고정 창. 인스턴스 하나 전제이고 재기동하면 초기화된다. 서버를
 * 늘리면 Redis 카운터로 바꾼다. 창은 첫 요청 시각부터 고정이라 창 경계에서 최대 2배가
 * 통과할 수 있다 — 시작값(5건·3건)에서는 문제가 아니다.
 */
public class IpRateLimiter {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final String what;
    private final int maxPerWindow;
    private final Duration window;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * @param what 로그에 찍을 이름("공개 HOLD", "가입"). 배포 환경 로그에서 어느 경로의
     *             판정인지 가른다
     */
    protected IpRateLimiter(String what, int maxPerWindow, Duration window) {
        this.what = what;
        this.maxPerWindow = maxPerWindow;
        this.window = window;
    }

    /**
     * 이 IP 의 요청을 하나 더 센다.
     *
     * @return 상한 안이면 {@code true}. 넘으면 {@code false} 이고 그 요청도 셌다 —
     *         거절된 요청이 창을 늘리지는 않지만 상한을 넘긴 채로 유지된다
     */
    public boolean tryAcquire(String clientIp, Instant now) {
        Window current = windows.compute(clientIp, (ignored, existing) ->
                existing == null || existing.isExpiredAt(now, window)
                        ? new Window(now, 1)
                        : existing.plusOne());
        evictExpired(now);

        boolean allowed = current.count() <= maxPerWindow;
        if (allowed) {
            // 통과도 한 번은 보이게 — 배포 환경(INFO)에서 판정 IP 가 Docker 내부 주소가
            // 아니라 공인 주소인지 로그로 확인한다(작업지시-18 6절 14번).
            log.info("{} 요청. ip={} 창내={}/{}", what, masked(clientIp), current.count(), maxPerWindow);
        } else {
            log.warn("{} 속도 제한에 걸렸다. ip={} 창내={}/{} 창={}",
                    what, masked(clientIp), current.count(), maxPerWindow, window);
        }
        return allowed;
    }

    public Duration window() {
        return window;
    }

    /**
     * 만료된 창을 비운다. 요청이 올 때만 돈다 — 이 맵이 커지는 경로가 요청뿐이라 커지는
     * 그 순간에 함께 줄이면 된다(2GB 서버, {@code LoginAttemptLimiter} 와 같은 이유).
     */
    private void evictExpired(Instant now) {
        windows.entrySet().removeIf(entry -> entry.getValue().isExpiredAt(now, window));
    }

    /**
     * 로그용. 끝자리를 가린다 — IPv4 는 마지막 옥텟, IPv6 은 마지막 그룹. 개인정보를
     * 로그에 그대로 남기지 않되 어느 대역에서 왔는지는 보인다.
     */
    public static String masked(String ip) {
        if (ip == null) {
            return "?";
        }
        int cut = ip.contains(":") ? ip.lastIndexOf(':') : ip.lastIndexOf('.');
        return cut < 0 ? "***" : ip.substring(0, cut + 1) + "***";
    }

    private record Window(Instant startedAt, int count) {

        Window plusOne() {
            return new Window(startedAt, count + 1);
        }

        boolean isExpiredAt(Instant now, Duration window) {
            return !startedAt.plus(window).isAfter(now);
        }
    }
}
