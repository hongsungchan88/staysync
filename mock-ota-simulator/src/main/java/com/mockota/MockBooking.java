package com.mockota;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 채널이 발행하는 예약. <b>우리 도메인 타입이 아니다.</b>
 *
 * <p>{@code booking.domain.Reservation} 이나 {@code channel.port.InboundBooking} 을
 * 참조하지 않는다. 채널이 우리 타입을 아는 것은 말이 안 되고, 참조하면 12주차 어댑터가
 * 하는 "채널 형식 → 우리 형식" 변환이 아무 일도 하지 않게 된다.
 *
 * <p>JSON 은 {@code snake_case} 로 나간다({@code application.yml}). 상용 채널
 * 매니저들이 그렇게 쓰기도 하고, 우리 API 의 {@code camelCase} 와 달라야 어댑터가
 * 실제로 매핑을 하기 때문이다. 이름이 우연히 맞아떨어져 통과하는 일이 없어야 한다.
 *
 * @param bookingId  채널 측 예약 번호. 멱등성 키다. <b>중복될 수 있다</b> — 같은 번호로
 *                   두 번 보내는 것이 시나리오 하나다
 * @param roomId     채널 측 객실 식별자. 매핑 테이블이 우리 Unit 과 잇는다
 * @param revision   수정 버전. <b>역전된 순서로 나갈 수 있다</b>
 * @param status     {@code BOOKED} 또는 {@code CANCELLED}
 * @param emittedAt  시뮬레이터가 내보낸 시각. 동시 예약 다발을 확인할 때 쓴다
 */
public record MockBooking(
        String bookingId,
        String roomId,
        LocalDate checkIn,
        LocalDate checkOut,
        String guestName,
        int adults,
        int children,
        BigDecimal totalAmount,
        int revision,
        String status,
        Instant emittedAt) {

    public static final String BOOKED = "BOOKED";
    public static final String CANCELLED = "CANCELLED";

    /** 같은 예약의 다음 버전. {@code revision} 만 갈아 끼운다. */
    public MockBooking withRevision(int newRevision) {
        return new MockBooking(bookingId, roomId, checkIn, checkOut, guestName,
                adults, children, totalAmount, newRevision, status, Instant.now());
    }
}
