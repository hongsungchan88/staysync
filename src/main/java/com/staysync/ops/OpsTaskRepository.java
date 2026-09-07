package com.staysync.ops;

import com.staysync.ops.domain.OpsTask;
import com.staysync.ops.domain.TaskType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OpsTaskRepository extends JpaRepository<OpsTask, Long> {

    /**
     * 중복 확인용. 같은 예약에 같은 종류의 태스크가 이미 있는지.
     *
     * <p>{@code uq_task_reservation} 이 최종 방어선이고 이 확인은 평소를 맡는다.
     * 확인만 두면 동시에 들어온 두 번째가 통과하고, 인덱스만 두면 정상 동작인
     * 중복 전달이 예외로 올라온다. <b>둘 다 둔다</b> — 14주차 발송 이력과 같다.
     */
    Optional<OpsTask> findByReservationIdAndTaskType(Long reservationId, TaskType taskType);

    /**
     * 칸반 보드 한 장.
     *
     * <p>조직의 숙소로 좁힌 뒤 날짜와 담당자로 거른다.
     *
     * <p><b>날짜에 {@code null} 을 바인딩하지 않는다.</b> {@code :from is null or …} 로
     * 쓰면 PostgreSQL 이 그 매개변수의 자료형을 알 수 없다고 거절한다 — 비교 상대가
     * 없는 자리라 추론할 근거가 없다. 필터가 없으면 부르는 쪽이 넓은 경계를 준다.
     *
     * <p>기한이 열린 태스크({@code dueTo} 가 없는 것)는 날짜 범위로 걸러 내지 않는다.
     * 다음 예약이 아직 없다는 뜻이지 보드에서 사라질 이유가 아니다.
     */
    @Query("""
            select t from OpsTask t
            where t.propertyId in :propertyIds
              and (t.dueTo is null or t.dueTo >= :from)
              and (t.dueFrom is null or t.dueFrom < :to)
              and (:assignee is null or t.assigneeName = :assignee)
            order by t.dueFrom asc, t.id asc
            """)
    List<OpsTask> search(@Param("propertyIds") List<Long> propertyIds,
                         @Param("from") OffsetDateTime from,
                         @Param("to") OffsetDateTime to,
                         @Param("assignee") String assignee);
}
