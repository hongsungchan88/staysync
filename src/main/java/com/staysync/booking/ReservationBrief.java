package com.staysync.booking;

import java.time.LocalDate;

/**
 * 예약의 요약. booking 이 바깥에 내보내는 값 타입이다.
 *
 * <p>{@code booking.domain.Reservation} 을 그대로 넘기지 않는다. 모듈 내부 구현이고,
 * 넘기면 받는 쪽이 상태 전이 메서드까지 손에 쥔다.
 *
 * <p><b>연락처를 담지 않는다.</b> {@code guestName} 까지는 담는다 — 인박스가 누구와의
 * 대화인지 보여 줘야 하고 템플릿의 {@code {{guestName}}} 이 그 값을 쓴다. 전화번호와
 * 이메일은 {@code GuestRegistrar} 를 거쳐야 평문이 되고, 그 경로는 열지 않는다
 * (ADR 0007). 캘린더 막대가 담는 것과 같은 선이다.
 *
 * @param status {@code ReservationStatus} 의 이름. enum 자체는 내부 타입이다
 */
public record ReservationBrief(
        Long id,
        Long propertyId,
        Long unitId,
        Long guestId,
        String confirmationCode,
        String channelCode,
        String channelBookingId,
        String status,
        LocalDate checkIn,
        LocalDate checkOut,
        String guestName) {

    /** 재고를 쥐고 있는 상태인지. 자동 발송이 취소된 예약을 거를 때 쓴다. */
    public boolean isActive() {
        return "HOLD".equals(status) || "CONFIRMED".equals(status) || "CHECKED_IN".equals(status);
    }
}
