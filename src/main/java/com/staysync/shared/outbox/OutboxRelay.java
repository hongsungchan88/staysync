package com.staysync.shared.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 커밋된 이벤트를 꺼내 발행한다. 주기는 1초다(계획서 4.4).
 *
 * <p><b>최소 1회 전달이다.</b> 발행에 성공하고 {@code published_at} 을 적기 전에 죽으면
 * 다음 주기에 같은 이벤트가 다시 나간다. 정확히 1회를 보장하려면 발행과 표시가 한
 * 원자적 연산이어야 하는데 외부 시스템과 데이터베이스를 걸치는 이상 불가능하다.
 * 중복은 소비자의 멱등성으로 흡수한다는 것이 4.4 의 전제이며, 예약 수신은
 * {@code (channel_code, channel_booking_id)} 유니크 제약이 이미 그 역할을 한다.
 *
 * <p><b>순서 보장은 단일 스레드라는 전제 위에 있다.</b> 같은 애그리게이트의 취소가
 * 확정보다 먼저 나가면 채널 쪽 상태가 뒤집힌다. 지금은 한 스레드가
 * {@code created_at} 오름차순으로 하나씩 처리하므로 성립한다. <b>병렬로 바꾸면 이
 * 보장이 깨진다.</b> 그때는 애그리게이트 단위로 분배하거나 순서 키를 따로 두어야 한다.
 *
 * <p>인스턴스가 하나뿐이라 ShedLock 과 {@code FOR UPDATE SKIP LOCKED} 를 쓰지 않았다.
 * 여러 대가 되면 둘 다 필요하다. 릴레이가 동시에 돌면 같은 행을 집어 중복 발행하고,
 * 그건 최소 1회 전달의 정상 범위가 아니라 매 주기 벌어지는 낭비다.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** 한 주기에 발행할 최대 건수. 한 트랜잭션이 길어지지 않게 자른다. */
    static final int BATCH_LIMIT = 100;

    /**
     * 재시도 상한.
     *
     * <p>닿은 이벤트는 조회에서 빠지지만 <b>행은 지우지 않는다.</b> {@code last_error} 가
     * 남아 있어야 사람이 원인을 보고 다시 넣을 수 있다.
     */
    static final short RETRY_LIMIT = 10;

    private final OutboxEventRepository repository;
    private final DomainEventPublisher publisher;

    OutboxRelay(OutboxEventRepository repository, DomainEventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    /**
     * 주기 실행. 기본 1초다(계획서 4.4).
     *
     * <p>주기를 설정으로 뺀 이유는 <b>테스트에서 이 스케줄러를 멈춰야 하기</b> 때문이다.
     * {@code @EnableScheduling} 이 켜져 있어 테스트 컨텍스트에서도 1초마다 돌고, 그러면
     * 테스트가 직접 부르는 {@link #relayPending()} 과 같은 이벤트를 두고 경쟁한다.
     * 재시도 횟수가 예상보다 빨리 오르거나 경고 로그가 두 번 남는 식으로 어긋난다.
     */
    @Scheduled(fixedDelayString = "${staysync.outbox.relay-interval-ms:1000}",
            initialDelayString = "${staysync.outbox.relay-interval-ms:1000}")
    public void run() {
        relayPending();
    }

    /**
     * 스케줄러를 기다리지 않고 부를 수 있게 분리했다. 테스트가 이 메서드를 쓴다.
     *
     * @return 발행에 성공한 건수
     */
    @Transactional
    public int relayPending() {
        List<OutboxEvent> pending = repository.findPending(
                RETRY_LIMIT, PageRequest.of(0, BATCH_LIMIT));

        int published = 0;
        for (OutboxEvent event : pending) {
            if (publishOne(event)) {
                published++;
            }
        }

        if (published > 0) {
            log.debug("이벤트 {}건을 발행했다", published);
        }
        if (pending.size() == BATCH_LIMIT) {
            // 조용히 잘리면 밀린 이벤트가 쌓여도 정상으로 보인다. HOLD 만료 배치와 같다.
            log.warn("이벤트 발행이 한 주기 상한 {}건에 닿았다. 남은 건은 다음 주기로 넘긴다. "
                    + "이 로그가 계속 나오면 상한이나 주기를 조정해야 한다", BATCH_LIMIT);
        }
        return published;
    }

    /**
     * 한 건을 발행한다.
     *
     * <p>실패해도 예외를 올리지 않는다. 한 건이 막혔다고 뒤의 이벤트까지 멈추면 안 된다.
     * 대신 {@code retry_count} 를 올리고 사유를 남겨 다음 주기에 다시 시도한다.
     */
    private boolean publishOne(OutboxEvent event) {
        try {
            publisher.publish(event);
            event.markPublished(OffsetDateTime.now());
            return true;
        } catch (RuntimeException e) {
            boolean justReachedLimit = event.recordFailure(e.toString(), RETRY_LIMIT);
            if (justReachedLimit) {
                // 상한에 닿는 그 한 번만 남긴다. 매 주기 남기면 로그가 채워져
                // 정작 봐야 할 것이 묻힌다.
                log.error("이벤트 발행이 재시도 상한 {}회에 닿아 더 시도하지 않는다. "
                                + "id={} type={} 원인을 확인하고 수동으로 처리해야 한다",
                        RETRY_LIMIT, event.getId(), event.getEventType(), e);
            } else {
                log.warn("이벤트 발행에 실패했다. 다음 주기에 다시 시도한다. id={} 시도={}회",
                        event.getId(), event.getRetryCount());
            }
            return false;
        }
    }
}
