package com.staysync.booking;

import com.staysync.booking.domain.ReservationNight;
import com.staysync.booking.domain.ReservationNightId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationNightRepository
        extends JpaRepository<ReservationNight, ReservationNightId> {

    List<ReservationNight> findByReservationIdOrderByStayDateAsc(Long reservationId);

    /** 날짜가 바뀌면 옛 박 행을 지우고 다시 쓴다. */
    void deleteByReservationId(Long reservationId);
}
