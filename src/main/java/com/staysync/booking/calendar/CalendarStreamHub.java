package com.staysync.booking.calendar;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 열려 있는 캘린더 화면들. 조직 단위로 묶어 둔다.
 *
 * <p><b>조직으로 나눠 두는 것이 이 클래스의 존재 이유다.</b> 구독을 {@code orgId} 로
 * 좁히지 않으면 남의 조직 예약이 화면에 뜬다. 조회 API 는 {@code OwnedResources} 로
 * 막아 두었는데 실시간 경로에 구멍이 남는 모양이고, <b>새는 쪽에서는 아무 증상이 없다.</b>
 * 그래서 보낼 때마다 조직을 고르는 것이 아니라, 애초에 조직별로 갈라 담는다.
 *
 * <p><b>단일 인스턴스 전제다.</b> 구독자를 이 JVM 의 메모리에 들고 있으므로 여러 대가
 * 되면 인스턴스마다 구독자가 갈라져, 다른 인스턴스가 처리한 예약은 내 화면에 오지
 * 않는다. {@code OutboxRelay} 와 {@code LocalUnitLock} 이 같은 전제 위에 있다.
 * 그때는 Redis 발행/구독 같은 것이 필요하다. 근거는 ADR 0010.
 */
@Component
public class CalendarStreamHub {

    private static final Logger log = LoggerFactory.getLogger(CalendarStreamHub.class);

    /**
     * 연결 유지 시간. 넉넉하지만 무한은 아니다.
     *
     * <p>무한으로 두면 죽은 연결이 영원히 남는다. 만료되면 브라우저가 다시 붙고,
     * 그때 캘린더를 다시 조회하므로 그동안의 변경도 함께 복구된다.
     */
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, List<SseEmitter>> byOrg = new ConcurrentHashMap<>();

    /** 이 조직의 화면 하나가 연결됐다. */
    public SseEmitter subscribe(Long orgId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> emitters =
                byOrg.computeIfAbsent(orgId, id -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        emitter.onCompletion(() -> remove(orgId, emitter));
        emitter.onTimeout(() -> remove(orgId, emitter));
        emitter.onError(e -> remove(orgId, emitter));

        // 첫 프레임을 바로 보낸다. 프록시가 응답 헤더만으로는 스트림을 흘리지 않는
        // 경우가 있어, 브라우저가 "연결됐다"를 늦게 아는 것을 막는다.
        send(emitter, "connected", Map.of("orgId", orgId));
        return emitter;
    }

    /**
     * 이 조직의 화면들에 사건을 알린다.
     *
     * <p>보내는 것은 <b>무엇이 바뀌었는지가 아니라 바뀌었다는 사실</b>이다. 화면은 이걸
     * 받고 캘린더를 다시 조회한다. 변경 내용을 실어 화면이 그것만 반영하게 만들면
     * Outbox 의 최소 1회 전달과 순서 보장을 화면 쪽에도 다시 구현해야 한다.
     */
    public void broadcast(Long orgId, String eventType, Long propertyId) {
        List<SseEmitter> emitters = byOrg.get(orgId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        Map<String, Object> payload = Map.of("type", eventType, "propertyId", propertyId);
        for (SseEmitter emitter : emitters) {
            send(emitter, "calendar", payload);
        }
    }

    /** 지금 이 조직에 연결된 화면 수. 테스트와 운영 확인용이다. */
    public int subscriberCount(Long orgId) {
        List<SseEmitter> emitters = byOrg.get(orgId);
        return emitters == null ? 0 : emitters.size();
    }

    /**
     * 한 구독자에게 보낸다. <b>실패해도 예외를 올리지 않는다.</b>
     *
     * <p>{@code DomainEventPublisher} 의 계약은 "실패를 삼키지 말라"이지만, 여기서
     * 실패하는 것은 이미 끊긴 브라우저다. 릴레이에 실패로 올리면 그 이벤트가 다음
     * 주기에 다시 나가고, 살아 있는 다른 화면들이 같은 알림을 또 받는다. 끊긴 화면은
     * 다시 붙을 때 캘린더를 통째로 다시 조회하므로 그쪽으로 복구된다 — 놓친 이벤트를
     * 재생하지 않는다는 결정이 여기서도 같다.
     */
    private void send(SseEmitter emitter, String name, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(payload));
        } catch (IOException | IllegalStateException e) {
            // 이미 끊겼거나 닫힌 연결이다. 목록에서 빼는 것으로 충분하다.
            log.debug("실시간 연결이 끊겨 있어 건너뛴다. {}", e.toString());
            emitter.completeWithError(e);
        }
    }

    private void remove(Long orgId, SseEmitter emitter) {
        List<SseEmitter> emitters = byOrg.get(orgId);
        if (emitters != null) {
            emitters.remove(emitter);
            // 비었다고 키를 지우지 않는다. 지우는 순간 다른 스레드가 방금 만든 목록에
            // 넣고 있을 수 있고, 그러면 그 구독자가 조용히 사라진다.
        }
    }
}
