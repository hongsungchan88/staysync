package com.staysync.channel.port;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 채널에서 들어온 예약을 표준화한 형태.
 *
 * <p>어댑터마다 원본 형식이 다르지만 이 타입으로 변환한 뒤에는
 * 도메인 로직이 채널을 구분할 필요가 없다.
 *
 * @param bookingId       채널 측 예약 식별자. iCal 은 VEVENT 의 UID 를 쓴다
 * @param externalUnitId  채널 측 상품 식별자. 매핑 테이블로 우리 Unit 을 찾는다
 * @param revision        수정 버전. iCal 처럼 버전 개념이 없는 채널은 내용 해시로 대신한다
 * @param isBlock         예약이 아니라 단순 차단인 경우 true
 * @param isCancellation  취소 통지인 경우 true
 */
public record InboundBooking(
        String bookingId,
        String externalUnitId,
        LocalDate checkIn,
        LocalDate checkOut,
        String guestName,
        int adults,
        int children,
        BigDecimal totalAmount,
        int revision,
        boolean isBlock,
        boolean isCancellation,
        String rawPayload) {

    public InboundBooking {
        if (bookingId == null || bookingId.isBlank()) {
            throw new IllegalArgumentException("채널 예약 식별자는 필수입니다. 멱등성 키로 쓰입니다.");
        }
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("숙박 기간이 올바르지 않습니다: " + checkIn + " ~ " + checkOut);
        }
        if (totalAmount == null) {
            totalAmount = BigDecimal.ZERO;
        }
    }
}
