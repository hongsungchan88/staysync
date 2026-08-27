package com.staysync.shared.outbox;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * 발행 대기 중인 이벤트. {@code created_at} 오름차순이다.
     *
     * <p>순서가 이 질의의 요점이다. 같은 애그리게이트의 취소가 확정보다 먼저 나가면
     * 채널 쪽 상태가 뒤집힌다. {@code idx_outbox_pending} 이 이 질의를 위한 부분
     * 인덱스다({@code published_at IS NULL} 조건).
     *
     * <p>재시도 상한에 닿은 이벤트는 빠진다. 행은 남지만 조회되지 않아 뒤에 쌓인
     * 정상 이벤트를 막지 않는다. 사람이 {@code last_error} 를 보고 처리한다.
     *
     * <p>인스턴스가 하나뿐이라 {@code FOR UPDATE SKIP LOCKED} 를 걸지 않았다.
     * 여러 대가 되면 두 릴레이가 같은 행을 집어 중복 발행하므로 그때 필요해진다.
     */
    @Query("""
            select e from OutboxEvent e
            where e.publishedAt is null
              and e.retryCount < :retryLimit
            order by e.createdAt asc, e.id asc
            """)
    List<OutboxEvent> findPending(@Param("retryLimit") short retryLimit, Pageable pageable);

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByIdAsc(String aggregateType,
                                                                    Long aggregateId);
}
