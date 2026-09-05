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
}
