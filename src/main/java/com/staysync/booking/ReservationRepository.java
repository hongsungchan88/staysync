package com.staysync.booking;

import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.ReservationStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** 멱등성 확인용. 같은 예약을 여러 번 받아도 한 건만 만들기 위해 쓴다. */
    Optional<Reservation> findByChannelCodeAndChannelBookingId(String channelCode,
                                                               String channelBookingId);

    Optional<Reservation> findByConfirmationCode(String confirmationCode);

    @Query("""
            select r from Reservation r
            where r.propertyId = :propertyId
              and r.status in :statuses
              and r.period.checkIn < :to
              and r.period.checkOut > :from
            order by r.period.checkIn asc
            """)
    List<Reservation> findOverlapping(@Param("propertyId") Long propertyId,
                                      @Param("from") LocalDate from,
                                      @Param("to") LocalDate to,
                                      @Param("statuses") List<ReservationStatus> statuses);

    /**
     * 만료된 HOLD. 한 주기에 처리할 건수를 제한하려고 {@link Pageable} 을 받는다.
     *
     * <p>밀린 HOLD 가 많을 때 전부 한 번에 처리하면 락을 오래 쥐어 그 판매 단위의 예약이
     * 막힌다. 남은 것은 다음 주기로 넘긴다.
     */
    @Query("""
            select r from Reservation r
            where r.status = com.staysync.booking.domain.ReservationStatus.HOLD
              and r.holdExpiresAt < :now
            order by r.holdExpiresAt asc
            """)
    List<Reservation> findExpiredHolds(@Param("now") OffsetDateTime now, Pageable pageable);

    /** 조직 스코핑된 목록. reservation 에는 org_id 가 없어 property 를 거쳐 좁힌다. */
    @Query("""
            select r from Reservation r
            where r.propertyId in :propertyIds
              and (:status is null or r.status = :status)
              and (:channelCode is null or r.channelCode = :channelCode)
              and (:from is null or r.period.checkOut > :from)
              and (:to is null or r.period.checkIn < :to)
            order by r.period.checkIn desc, r.id desc
            """)
    List<Reservation> search(@Param("propertyIds") List<Long> propertyIds,
                             @Param("status") ReservationStatus status,
                             @Param("channelCode") String channelCode,
                             @Param("from") LocalDate from,
                             @Param("to") LocalDate to);
}
