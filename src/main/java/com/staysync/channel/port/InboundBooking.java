package com.staysync.channel.port;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 채널에서 들어온 예약을 표준화한 형태.
 *
 * <p>어댑터마다 원본 형식이 다르지만 이 타입으로 변환한 뒤에는
 * 도메인 로직이 채널을 구분할 필요가 없다.
 *
 * <p>P3 13주차에 {@code isBlock} 을 지웠다. 예약과 차단을 구분하지 않기로 했기
 * 때문이다(작업지시 10 의 5절 3번, ADR 0013). 조사-02 가 확인한 실제 에어비앤비
 * 발행물에 구분할 정보가 없어서 키워드로 나누는 로직은 <b>검증할 수 없는 코드</b>가
 * 된다. 재고를 막는다는 점에서 둘은 같으므로 전부 예약으로 받는다.
 *
 * @param bookingId       채널 측 예약 식별자. iCal 은 VEVENT 의 UID 를 쓴다
 * @param externalUnitId  채널 측 상품 식별자. 매핑 테이블로 우리 Unit 을 찾는다.
 *                        발행물에 상품 식별자가 없는 채널(iCal)은 {@code null} 이고,
 *                        수신부가 그 연결의 매핑으로 채운다
 * @param revision        수정 버전. <b>{@code null} 은 "이 채널에 버전이 없다"</b>는
 *                        뜻이고, 그때는 크기 비교 대신 값이 달라졌는지로 판정한다.
 *                        계획서 13.4 처럼 내용 해시를 버전 자리에 넣지 않는다 —
 *                        해시에는 순서가 없어서 날짜가 바뀐 뒤의 해시가 우연히 작으면
 *                        수정이 조용히 무시된다(ADR 0013)
 * @param isCancellation  취소 통지인 경우 true. 스냅샷 채널은 이 값을 쓰지 않고
 *                        목록에서 사라진 것을 취소로 본다
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
        Integer revision,
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
