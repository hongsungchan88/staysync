package com.staysync.booking;

import com.staysync.booking.domain.InventoryLedger;
import com.staysync.booking.domain.InventoryLedgerId;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface InventoryLedgerRepository
        extends JpaRepository<InventoryLedger, InventoryLedgerId> {

    /**
     * 재고 행을 배타 잠금과 함께 조회한다. 방어 계층의 2계층에 해당한다.
     *
     * <p>정렬 순서가 중요하다. 여러 날짜에 락을 걸 때 스레드마다 순서가 다르면
     * 교착 상태가 발생하므로 날짜 오름차순으로 고정한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("""
            select l from InventoryLedger l
            where l.unitId = :unitId and l.stayDate in :dates
            order by l.stayDate asc
            """)
    List<InventoryLedger> findForUpdate(@Param("unitId") Long unitId,
                                        @Param("dates") List<LocalDate> dates);

    @Query("""
            select l from InventoryLedger l
            where l.unitId in :unitIds and l.stayDate between :from and :to
            order by l.unitId asc, l.stayDate asc
            """)
    List<InventoryLedger> findGrid(@Param("unitIds") List<Long> unitIds,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);
}
