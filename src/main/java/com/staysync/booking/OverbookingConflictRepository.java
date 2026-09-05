package com.staysync.booking;

import com.staysync.booking.domain.OverbookingConflict;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OverbookingConflictRepository extends JpaRepository<OverbookingConflict, Long> {

    /**
     * 캘린더가 그릴 범위의 미해소 충돌.
     *
     * <p>{@code idx_conflict_open} 이 이 경로를 받는다. 해소된 것은 셀에 표시하지
     * 않는다 — 운영자가 이미 처리한 것을 계속 붉게 두면 표시가 의미를 잃는다.
     */
    @Query("""
            select c from OverbookingConflict c
            where c.propertyId = :propertyId
              and c.stayDate between :from and :to
              and c.status = 'OPEN'
            """)
    List<OverbookingConflict> findOpenIn(@Param("propertyId") Long propertyId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);

    List<OverbookingConflict> findByUnitIdOrderByStayDateAsc(Long unitId);

    /**
     * 조직이 아직 풀지 않은 충돌 전부. 관리 화면이 쓴다.
     *
     * <p>{@code overbooking_conflict} 에 {@code org_id} 가 없어 숙소로 좁힌다.
     * {@code unit}·{@code rate_plan} 과 같은 자리다.
     *
     * <p>지나간 날짜도 뺀 목록이 아니다. 어제 날짜의 충돌도 사람이 어떻게 처리했는지
     * 기록을 남겨야 해서다 — 조용히 사라지면 그날 무슨 일이 있었는지 알 수 없다.
     */
    @Query("""
            select c from OverbookingConflict c
            where c.propertyId in :propertyIds
              and c.status = 'OPEN'
            order by c.stayDate asc, c.id asc
            """)
    List<OverbookingConflict> findOpenOf(@Param("propertyIds") List<Long> propertyIds);
}
