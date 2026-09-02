package com.staysync.pricing;

import com.staysync.pricing.domain.RateCalendar;
import com.staysync.pricing.domain.RateCalendarId;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RateCalendarRepository extends JpaRepository<RateCalendar, RateCalendarId> {

    /**
     * 요금제 여러 개의 기간 요금을 한 번에 읽는다.
     *
     * <p>캘린더가 셀마다 질의하지 않게 하는 것이 이 메서드의 목적이다. 기본키
     * {@code (rate_plan_id, stay_date)} 의 범위 스캔이다.
     */
    @Query("""
            select r from RateCalendar r
            where r.ratePlanId in :ratePlanIds and r.stayDate between :from and :to
            order by r.ratePlanId asc, r.stayDate asc
            """)
    List<RateCalendar> findGrid(@Param("ratePlanIds") List<Long> ratePlanIds,
                                @Param("from") LocalDate from,
                                @Param("to") LocalDate to);
}
