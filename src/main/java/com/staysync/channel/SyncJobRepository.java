package com.staysync.channel;

import com.staysync.channel.domain.SyncJob;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SyncJobRepository extends JpaRepository<SyncJob, Long> {

    /**
     * 돌릴 작업을 집는다. 계획서 13.3 의 쿼리에 <b>순서 보장 조건이 더해져 있다.</b>
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 는 워커가 둘이어도 같은 행을 두 번 집지 않게
     * 한다. 인스턴스는 하나지만 개발 중에 앱을 두 번 띄우는 실수가 실제로 일어나고,
     * 비용은 쿼리 한 줄이다.
     *
     * <p><b>{@code NOT EXISTS} 가 같은 연결의 순서를 지킨다.</b> 이게 없으면 앞선 작업이
     * 실패해 60초 뒤로 밀린 사이 뒤 작업이 나가고, 60초 뒤 <b>옛 값이 새 값을 덮어쓴다.</b>
     * 채널 쪽 요금이 틀리는데 우리 쪽 로그는 전부 성공이라 아무 증상이 없다. 12주차
     * 완료 조건 2의 "최종 정합성"이 이 조건 위에 선다.
     *
     * <p>그래서 연결당 맨 앞의 한 건만 나온다. 워커가 따로 연결별로 묶어 직렬화할
     * 필요가 없다.
     *
     * <p>{@code attempt} 를 여기서 올린다. 집는 것과 시도 횟수를 세는 것이 한 문장이어야
     * 워커가 죽어도 횟수가 새지 않는다.
     */
    @Modifying
    @Query(value = """
            UPDATE sync_job SET status = 'RUNNING', attempt = attempt + 1
            WHERE id IN (
                SELECT j.id FROM sync_job j
                WHERE j.status = 'PENDING' AND j.next_run_at <= now()
                  AND NOT EXISTS (
                      SELECT 1 FROM sync_job earlier
                      WHERE earlier.connection_id = j.connection_id
                        AND earlier.id < j.id
                        AND earlier.status IN ('PENDING', 'RUNNING')
                  )
                ORDER BY j.next_run_at, j.id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<SyncJob> claimBatch(@Param("limit") int limit);

    /**
     * 아직 끝나지 않은 같은 작업이 있는지.
     *
     * <p>{@code uq_syncjob_pending} 이 최종 방어선이고 이 확인은 예외 대신 조용히
     * 넘기기 위한 것이다. 중복 이벤트는 오류가 아니라 정상 동작이다.
     */
    @Query("""
            select count(j) > 0 from SyncJob j
            where j.connectionId = :connectionId
              and j.idempotencyKey = :key
              and j.status in (com.staysync.channel.domain.SyncJobStatus.PENDING,
                               com.staysync.channel.domain.SyncJobStatus.RUNNING)
            """)
    boolean existsOutstanding(@Param("connectionId") Long connectionId, @Param("key") String key);

    List<SyncJob> findByConnectionIdOrderByIdAsc(Long connectionId);
}
