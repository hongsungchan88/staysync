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
 * <p>{@code blocks} 는 P5 17주차에 더했다. 게시 리스팅의 iCal 은 예약과 호스트 차단이
 * 같은 {@code VEVENT} 로 오고 {@code SUMMARY} 로만 갈린다. 예약으로 넣으면 리포트가
 * 틀리고, 버리면 대량 소실 방어의 기준값이 흔들린다 — 그래서 따로 싣고
 * {@link #eventCount()} 는 둘을 합친다.
 *
 * @param unchanged 채널이 "바뀐 것 없음"으로 답했다. {@code bookings} 는 비어 있고
 *                  <b>그 사실로 아무것도 판단하면 안 된다</b>
 * @param etag      다음 조건부 요청에 쓸 값. 채널이 주지 않으면 {@code null}
 * @param blocks    예약이 아닌 일정. 지금은 세기만 하고 재고에 반영하지 않는다
 */
public record BookingFeed(boolean unchanged, String etag,
                          List<InboundBooking> bookings, List<InboundBlock> blocks) {

    public BookingFeed {
        bookings = bookings == null ? List.of() : List.copyOf(bookings);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    /**
     * 발행물의 일정 수. 대량 소실 방어와 {@code last_event_count} 의 단위다.
     *
     * <p>예약만 세면 안 된다. 차단이 예약으로 바뀌는 날(게스트가 그 날짜를 잡는다)
     * 예약 수는 늘고 차단 수는 줄어 합은 같은데, 예약만 세던 기준값과 비교하면
     * 방어가 엉뚱하게 걸리거나 안 걸린다.
     */
    public int eventCount() {
        return bookings.size() + blocks.size();
    }

    /**
     * 채널이 304 로 답했다. 직전에 받은 것이 그대로 유효하다.
     *
     * <p>이름이 {@code unchanged} 가 아닌 이유는 레코드 컴포넌트와 겹치기 때문이다.
     */
    public static BookingFeed notModified() {
        return new BookingFeed(true, null, List.of(), List.of());
    }

    public static BookingFeed of(String etag, List<InboundBooking> bookings) {
        return new BookingFeed(false, etag, bookings, List.of());
    }

    /** 예약과 차단이 한 발행물에 섞여 오는 채널용. iCal 이 쓴다. */
    public static BookingFeed of(String etag, List<InboundBooking> bookings, List<InboundBlock> blocks) {
        return new BookingFeed(false, etag, bookings, blocks);
    }

    /** 조건부 요청을 하지 않는 채널용. Mock 과 Channex 가 쓴다. */
    public static BookingFeed of(List<InboundBooking> bookings) {
        return new BookingFeed(false, null, bookings, List.of());
    }
}
