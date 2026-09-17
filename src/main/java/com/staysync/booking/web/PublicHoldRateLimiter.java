package com.staysync.booking.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 공개 HOLD 생성의 속도 제한. 클라이언트 IP 당 시간 창 안의 요청 수를 센다(작업지시-18 D).
 *
 * <p>{@code POST /public/booking/{id}/hold} 는 로그인 없이 재고를 15분씩 잡는다. 반복하면
 * 업체의 실제 판매 날짜를 계속 비워 둘 수 있다 — 결제까지 가지 않아도 된다. 이 클래스는
 * 그 반복을 IP 하나 안에서 막는 얇은 한 겹이다. <b>여러 IP 로 나눠 오는 공격은 막지
 * 못한다</b>(5절 2번). 그것은 판매 단계에서 다시 본다.
 *
 * <p><b>{@code LoginAttemptLimiter} 를 재사용하지 않는다.</b> identity 내부 타입이고,
 * 실패를 세어 잠그는 장치라 성공 요청에 겨누면 정상 손님이 잠기며, 창과 상한이 로그인과
 * 공유된 상수다. 여기는 성공·실패를 가리지 않고 <b>요청 자체</b>를 센다.
 *
 * <p>판정은 락·트랜잭션 밖(컨트롤러)에서 한다. 넘으면 HOLD 를 만들지 않고 429 다.
 *
 * <p>ponytail: 메모리 고정 창. 인스턴스 하나 전제이고 재기동하면 초기화된다. 서버를
 * 늘리면 Redis 카운터로 바꾼다. 창은 첫 요청 시각부터 고정이라 창 경계에서 최대 2배가
 * 통과할 수 있다 — 시작값 5건에서는 문제가 아니다.
 */
@Component
public class PublicHoldRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PublicHoldRateLimiter.class);

    private final int maxPerWindow;
    private final Duration window;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    PublicHoldRateLimiter(
            @Value("${staysync.public-booking.hold-limit.max:5}") int maxPerWindow,
            @Value("${staysync.public-booking.hold-limit.window:10m}") Duration window) {
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
            // 아니라 공인 주소인지 로그로 확인한다(6절 14번).
            log.info("공개 HOLD 요청. ip={} 창내={}/{}", masked(clientIp), current.count(), maxPerWindow);
        } else {
            log.warn("공개 HOLD 속도 제한에 걸렸다. ip={} 창내={}/{} 창={}",
                    masked(clientIp), current.count(), maxPerWindow, window);
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
    static String masked(String ip) {
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
