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

    /**
     * 그날 체크인하는, 아직 살아 있는 예약. 시간 기반 자동 발송이 쓴다.
     *
     * <p><b>취소·만료된 예약은 빠진다.</b> 체크인 하루 전 알림이 취소된 예약에 나가는
     * 것이 이 기능의 가장 흔한 사고이고, 나간 뒤에는 되돌릴 수 없다. 거르는 자리를
     * 쿼리에 두면 부르는 쪽이 빠뜨릴 수 없다.
     */
    @Query("""
            select r from Reservation r
            where r.period.checkIn = :date and r.status in :statuses
            order by r.id asc
            """)
    List<Reservation> findActiveByCheckIn(@Param("date") LocalDate date,
                                          @Param("statuses") List<ReservationStatus> statuses);

    @Query("""
            select r from Reservation r
            where r.period.checkOut = :date and r.status in :statuses
            order by r.id asc
            """)
    List<Reservation> findActiveByCheckOut(@Param("date") LocalDate date,
                                           @Param("statuses") List<ReservationStatus> statuses);

    /**
     * 그 판매 단위의 다음 체크인. 청소 기한의 끝이다.
     *
     * <p>취소·만료된 예약은 빠진다. 오지 않을 손님 때문에 청소 기한이 앞당겨지면
     * 안 된다.
     */
    @Query("""
            select min(r.period.checkIn) from Reservation r
            where r.unitId = :unitId
              and r.period.checkIn >= :onOrAfter
              and r.status in :statuses
            """)
    LocalDate findNextCheckIn(@Param("unitId") Long unitId,
                              @Param("onOrAfter") LocalDate onOrAfter,
                              @Param("statuses") List<ReservationStatus> statuses);

    /**
     * 이 판매 단위에서 그 채널이 만든, 아직 재고를 쥐고 있는 예약.
     *
     * <p>스냅샷 채널(iCal)의 취소 판정이 쓴다. 발행물에 없는 것을 취소하려면 먼저
     * "지금 우리가 들고 있는 것"이 무엇인지 알아야 한다.
     *
     * <p>이미 취소·만료된 예약은 제외한다. 포함하면 매 주기마다 같은 예약을 다시
     * 취소하려 들고, 그때마다 감사 기록이 한 줄씩 쌓인다.
     */
    @Query("""
            select r from Reservation r
            where r.unitId = :unitId
              and r.channelCode = :channelCode
              and r.status in (com.staysync.booking.domain.ReservationStatus.HOLD,
                               com.staysync.booking.domain.ReservationStatus.CONFIRMED,
                               com.staysync.booking.domain.ReservationStatus.CHECKED_IN)
            order by r.id asc
            """)
    List<Reservation> findActiveOfChannel(@Param("unitId") Long unitId,
                                          @Param("channelCode") String channelCode);

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

    /**
     * 예약일부터 체크인일까지 평균 일수. 리포트의 리드타임이다.
     *
     * <p>박이 아니라 <b>예약</b>이 단위다. 리드타임은 예약 하나의 성질이고, 박으로
     * 세면 오래 묵는 예약이 평균을 끌어당긴다.
     *
     * <p><b>네이티브 질의다.</b> 날짜 사이의 일수는 JPQL 로 이식성 있게 쓸 수 없다.
     * 파라미터는 전부 바인딩이고 문자열을 이어 붙이지 않는다.
     */
    @Query(value = """
            SELECT avg(r.check_in - r.created_at::date)
            FROM reservation r
            WHERE r.property_id IN (:propertyIds)
              AND r.status IN (:statuses)
              AND r.check_in BETWEEN :from AND :to
            """, nativeQuery = true)
    Double averageLeadTimeDays(@Param("propertyIds") List<Long> propertyIds,
                               @Param("statuses") List<String> statuses,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to);

    /**
     * 취소율의 분모. <b>체크인 날짜</b> 기준이다.
     *
     * <p>리포트 화면은 기간 하나로 지표 여섯을 함께 보여 준다. 그 기간의 뜻이 지표마다
     * 다르면 숫자가 서로 맞지 않는다 — 점유율은 "그 기간에 묵은 박"인데 취소율만
     * "그 기간에 <b>예약한</b> 건"이면, 다음 달 리포트의 취소율이 <b>언제나 0</b>이다.
     * 그 달에 예약된 건이 아직 없기 때문이다. 실제로 그렇게 나왔다.
     *
     * <p>그래서 <b>기간의 뜻을 숙박으로 통일한다.</b> "8월 취소율"은 8월에 묵기로 했던
     * 예약 중 취소된 비율이다. 취소된 예약도 체크인 날짜를 그대로 들고 있다.
     */
    @Query("""
            select count(r) from Reservation r
            where r.propertyId in :propertyIds
              and r.status not in :excluded
              and r.period.checkIn between :from and :to
            """)
    long countBooked(@Param("propertyIds") List<Long> propertyIds,
                     @Param("excluded") List<ReservationStatus> excluded,
                     @Param("from") LocalDate from,
                     @Param("to") LocalDate to);

    /** 취소율의 분자. 같은 기준이다. */
    @Query("""
            select count(r) from Reservation r
            where r.propertyId in :propertyIds
              and r.status = com.staysync.booking.domain.ReservationStatus.CANCELLED
              and r.period.checkIn between :from and :to
            """)
    long countCancelled(@Param("propertyIds") List<Long> propertyIds,
                        @Param("from") LocalDate from,
                        @Param("to") LocalDate to);

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
