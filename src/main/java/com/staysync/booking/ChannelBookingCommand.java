package com.staysync.booking;

import com.staysync.booking.domain.StayPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 채널에서 들어온 예약을 booking 에 넘기는 명령. <b>booking 이 바깥에 공개하는 값 타입</b>이다.
 *
 * <p>{@code channel.port.InboundBooking} 을 그대로 받지 않는다. 그건 channel 모듈의
 * 타입이고, booking 이 그걸 참조하면 의존 방향이 뒤집힌다(방향은 channel → booking 이다).
 * 채널 쪽 객실 식별자를 우리 {@code unitId} 로 옮기는 것도 매핑 테이블을 가진
 * channel 의 일이라, 여기 오는 시점에는 이미 우리 식별자다.
 *
 * <p><b>날짜를 {@code LocalDate} 로 받는다.</b> {@code StayPeriod} 는
 * {@code booking.domain} 의 타입이라 channel 이 참조하면 {@code ModularityTest} 가
 * 깨진다. 기간으로 묶는 것은 이 안에서 한다.
 *
 * @param channelBookingId 채널 측 예약번호. {@code channelCode} 와 묶여 멱등성 키가 된다
 * @param revision         채널 측 수정 버전. 낮은 것이 나중에 와도 무시된다
 * @param cancellation     취소 통지면 참
 */
public record ChannelBookingCommand(
        Long propertyId,
        Long unitId,
        String channelCode,
        String channelBookingId,
        LocalDate checkIn,
        LocalDate checkOut,
        BigDecimal totalAmount,
        int revision,
        boolean cancellation) {

    public ChannelBookingCommand {
        if (channelCode == null || channelCode.isBlank()) {
            throw new IllegalArgumentException("채널 코드는 필수입니다.");
        }
        if (channelBookingId == null || channelBookingId.isBlank()) {
            // 멱등성 키가 없으면 같은 예약이 올 때마다 새 예약이 된다.
            throw new IllegalArgumentException("채널 예약번호는 필수입니다. 멱등성 키로 쓰입니다.");
        }
        if (totalAmount == null) {
            totalAmount = BigDecimal.ZERO;
        }
    }

    /** 기간으로 묶는다. booking 안에서만 쓰인다. */
    StayPeriod period() {
        return new StayPeriod(checkIn, checkOut);
    }
}
