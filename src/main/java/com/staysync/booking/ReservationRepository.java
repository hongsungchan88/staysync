package com.staysync.booking;

import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.ReservationStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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

    @Query("""
            select r from Reservation r
            where r.status = com.staysync.booking.domain.ReservationStatus.HOLD
              and r.holdExpiresAt < :now
            """)
    List<Reservation> findExpiredHolds(@Param("now") OffsetDateTime now);
}
