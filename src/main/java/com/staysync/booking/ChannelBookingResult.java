package com.staysync.booking;

import java.time.LocalDate;
import java.util.List;

/**
 * 채널 예약 수신의 결과. 계획서 13.2 의 {@code IngestResult} 다.
 *
 * @param conflictDates 재고를 넘겨 받아들인 날짜들. 비어 있지 않으면
 *                      {@code overbooking_conflict} 에 행이 남았다는 뜻이다
 */
public record ChannelBookingResult(Outcome outcome, Long reservationId, List<LocalDate> conflictDates) {

    public enum Outcome {
        /** 새 예약이 생겼다. */
        CREATED,
        /** 더 높은 revision 이 와서 반영했다. */
        UPDATED,
        /** 취소 통지를 반영했다. */
        CANCELLED,
        /** 이미 아는 예약이고 revision 도 새롭지 않다. 아무것도 하지 않았다. */
        DUPLICATE,
        /** 받아들였지만 재고를 넘겼다. 예약은 살아 있고 충돌이 기록됐다. */
        CONFLICT
    }

    static ChannelBookingResult of(Outcome outcome, Long reservationId) {
        return new ChannelBookingResult(outcome, reservationId, List.of());
    }

    static ChannelBookingResult conflict(Long reservationId, List<LocalDate> dates) {
        return new ChannelBookingResult(Outcome.CONFLICT, reservationId, List.copyOf(dates));
    }
}
