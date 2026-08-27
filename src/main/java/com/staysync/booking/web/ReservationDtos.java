package com.staysync.booking.web;

import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.ReservationNight;
import com.staysync.booking.domain.ReservationStatus;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 예약 API 의 요청·응답 본문.
 *
 * <p>게스트 연락처는 응답에 싣지 않는다. 복호화해서 내보내려면 마스킹 규칙과 조회 기록이
 * 함께 있어야 하는데 둘 다 P5 다. 지금은 이름까지만 보낸다.
 */
final class ReservationDtos {

    private ReservationDtos() {
    }

    // --- 요청 ----------------------------------------------------------------

    /**
     * 수기 등록.
     *
     * <p>조직 식별자를 받지 않는다. 인증 주체에서 가져오며, 숙소가 그 조직의 것인지도
     * 서버가 확인한다.
     */
    record CreateReservationRequest(
            @NotNull Long propertyId,
            @NotNull Long unitId,
            @NotNull LocalDate checkIn,
            @NotNull LocalDate checkOut,
            @NotNull @DecimalMin("0") BigDecimal totalAmount,
            @NotNull @Min(1) @Max(99) Short adults,
            @Min(0) @Max(99) Short children,
            @Size(max = 120) String guestName,
            @Size(max = 40) String guestPhone,
            @Email @Size(max = 255) String guestEmail) {
    }

    /** 날짜와 인원 변경. 넘기지 않은 값은 그대로 둔다. */
    record UpdateReservationRequest(
            LocalDate checkIn,
            LocalDate checkOut,
            @Min(1) @Max(99) Short adults,
            @Min(0) @Max(99) Short children) {
    }

    // --- 응답 ----------------------------------------------------------------

    record ReservationSummary(
            Long id, Long propertyId, Long unitId, String confirmationCode,
            ReservationStatus status, String channelCode,
            LocalDate checkIn, LocalDate checkOut, int nights,
            short adults, short children,
            BigDecimal totalAmount, OffsetDateTime holdExpiresAt) {

        static ReservationSummary from(Reservation r) {
            return new ReservationSummary(
                    r.getId(), r.getPropertyId(), r.getUnitId(), r.getConfirmationCode(),
                    r.getStatus(), r.getChannelCode(),
                    r.getPeriod().checkIn(), r.getPeriod().checkOut(), r.getPeriod().nights(),
                    r.getAdults(), r.getChildren(),
                    r.getTotalAmount(), r.getHoldExpiresAt());
        }
    }

    /** 상세. 박별 스냅샷을 함께 싣는다. */
    record ReservationDetail(
            ReservationSummary reservation, String guestName, List<NightResponse> nights) {

        static ReservationDetail of(Reservation r, String guestName, List<ReservationNight> nights) {
            return new ReservationDetail(
                    ReservationSummary.from(r),
                    guestName,
                    nights.stream().map(NightResponse::from).toList());
        }
    }

    record NightResponse(LocalDate stayDate, BigDecimal price) {

        static NightResponse from(ReservationNight night) {
            return new NightResponse(night.getStayDate(), night.getPrice());
        }
    }
}
