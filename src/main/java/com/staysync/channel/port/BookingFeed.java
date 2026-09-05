package com.staysync.channel.port;

import java.util.List;

/**
 * 폴링 한 번의 결과.
 *
 * <p>{@code List<InboundBooking>} 하나로는 부족해서 만들었다. <b>"바뀐 것이 없다"와
 * "예약이 하나도 없다"가 같은 빈 목록이 되기 때문이다.</b> iCal 은 조건부 요청(304)에
 * 본문을 주지 않는데, 그걸 빈 목록으로 돌려주면 스냅샷 채널의 수신부가 발행물이
 * 통째로 비었다고 읽고 <b>모든 예약을 취소한다.</b>
 *
 * <p>{@code etag} 는 다음 요청의 {@code If-None-Match} 에 실린다. 값을 들고 있는 것은
 * 어댑터가 아니라 연결이다 — 어댑터가 메모리에 들고 있으면 재기동마다 전체를 다시
 * 받고, 그때 대량 소실 방어의 기준값도 함께 사라진다.
 *
 * @param unchanged 채널이 "바뀐 것 없음"으로 답했다. {@code bookings} 는 비어 있고
 *                  <b>그 사실로 아무것도 판단하면 안 된다</b>
 * @param etag      다음 조건부 요청에 쓸 값. 채널이 주지 않으면 {@code null}
 */
public record BookingFeed(boolean unchanged, String etag, List<InboundBooking> bookings) {

    public BookingFeed {
        bookings = bookings == null ? List.of() : List.copyOf(bookings);
    }

    /**
     * 채널이 304 로 답했다. 직전에 받은 것이 그대로 유효하다.
     *
     * <p>이름이 {@code unchanged} 가 아닌 이유는 레코드 컴포넌트와 겹치기 때문이다.
     */
    public static BookingFeed notModified() {
        return new BookingFeed(true, null, List.of());
    }

    public static BookingFeed of(String etag, List<InboundBooking> bookings) {
        return new BookingFeed(false, etag, bookings);
    }

    /** 조건부 요청을 하지 않는 채널용. Mock 과 Channex 가 쓴다. */
    public static BookingFeed of(List<InboundBooking> bookings) {
        return new BookingFeed(false, null, bookings);
    }
}
