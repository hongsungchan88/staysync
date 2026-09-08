package com.staysync.booking;

import com.staysync.booking.BookingStatistics.ChannelVolume;
import com.staysync.booking.BookingStatistics.SoldNights;
import com.staysync.booking.domain.ReservationNight;
import com.staysync.booking.domain.ReservationNightId;
import com.staysync.booking.domain.ReservationStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationNightRepository
        extends JpaRepository<ReservationNight, ReservationNightId> {

    List<ReservationNight> findByReservationIdOrderByStayDateAsc(Long reservationId);

    /** 날짜가 바뀌면 옛 박 행을 지우고 다시 쓴다. */
    void deleteByReservationId(Long reservationId);

    /**
     * 판매된 객실박과 매출. 리포트의 분자다.
     *
     * <p>예약과 박은 연관 필드가 아니라 식별자로 이어져 있어 조건으로 잇는다.
     * 박 행이 하루씩 쪼개져 있으므로 <b>기간에 걸친 예약도 그 기간에 든 박만</b>
     * 세어진다 — 자르는 코드가 따로 없다.
     */
    @Query("""
            select new com.staysync.booking.BookingStatistics$SoldNights(
                       count(n), coalesce(sum(n.price), 0))
            from ReservationNight n, Reservation r
            where r.id = n.reservationId
              and r.propertyId in :propertyIds
              and r.status in :statuses
              and n.stayDate between :from and :to
            """)
    SoldNights aggregateSold(@Param("propertyIds") List<Long> propertyIds,
                             @Param("statuses") List<ReservationStatus> statuses,
                             @Param("from") LocalDate from,
                             @Param("to") LocalDate to);

    /**
     * 채널별 건수와 매출.
     *
     * <p>건수는 <b>예약 수</b>다. 박 행을 세면 오래 묵은 예약이 여러 건으로 보인다.
     */
    @Query("""
            select new com.staysync.booking.BookingStatistics$ChannelVolume(
                       r.channelCode, count(distinct r.id), coalesce(sum(n.price), 0))
            from ReservationNight n, Reservation r
            where r.id = n.reservationId
              and r.propertyId in :propertyIds
              and r.status in :statuses
              and n.stayDate between :from and :to
            group by r.channelCode
            order by coalesce(sum(n.price), 0) desc, r.channelCode asc
            """)
    List<ChannelVolume> aggregateByChannel(@Param("propertyIds") List<Long> propertyIds,
                                           @Param("statuses") List<ReservationStatus> statuses,
                                           @Param("from") LocalDate from,
                                           @Param("to") LocalDate to);
}
