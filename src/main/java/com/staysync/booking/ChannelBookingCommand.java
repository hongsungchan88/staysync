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
 * @param guestName        채널이 알려 준 게스트 이름. <b>P4 15주차에 더했다.</b>
 *                         {@code channel.port.InboundBooking} 에는 처음부터 있었는데
 *                         이 경계에서 떨어지고 있었고, 그래서 채널 예약에는 게스트
 *                         레코드가 붙지 않아 {@code {{guestName}}} 을 쓰는 템플릿이
 *                         나가지 못했다 — <b>자동 발송의 절반이 죽어 있었다</b>
 *                         (작업지시 12 의 5절 1번). iCal 은 발행물에 이름이 없어
 *                         {@code null} 이고, 그때는 게스트를 만들지 않는다.
 *                         연락처는 받지 않는다 — 채널이 주지도 않고, 받으면
 *                         암호화 경계를 우회하는 두 번째 입구가 된다(ADR 0007)
 * @param totalAmount      채널이 준 총액. {@code null} 은 금액 미상이고 그대로 저장된다.
 *                         0 으로 바꾸지 않는다(작업지시-16 5절 1번)
 * @param adults           채널이 알려 준 성인 수. <b>0 은 모름</b>(iCal). 작업지시-21 B 에서 더했다 —
 *                         {@code InboundBooking} 에는 처음부터 있었는데 이 경계에서 떨어져 채널 예약이 전부
 *                         기본값 성인 2 로 저장됐다({@code guestName} 과 같은 모양, 확인-11 4절 ③)
 * @param children         채널이 알려 준 아동 수
 * @param revision         채널 측 수정 버전. 낮은 것이 나중에 와도 무시된다
 * @param cancellation     취소 통지면 참
 * @param supersedes       이 예약이 넘겨받을 수 있는 채널 코드들(작업지시-21 A). 같은 판매 단위에 체크인·
 *                         체크아웃이 똑같은 살아 있는 예약이 이 코드로 있으면 새로 만들지 않고 그 예약을
 *                         이 채널 예약으로 바꾼다. 비어 있으면 넘겨받지 않는다. channel 이 정한다 —
 *                         booking 은 어느 코드가 iCal 인지 모른다
 */
public record ChannelBookingCommand(
        Long propertyId,
        Long unitId,
        String channelCode,
        String channelBookingId,
        String guestName,
        LocalDate checkIn,
        LocalDate checkOut,
        BigDecimal totalAmount,
        int adults,
        int children,
        Integer revision,
        boolean cancellation,
        java.util.Set<String> supersedes) {

    public ChannelBookingCommand {
        if (channelCode == null || channelCode.isBlank()) {
            throw new IllegalArgumentException("채널 코드는 필수입니다.");
        }
        if (channelBookingId == null || channelBookingId.isBlank()) {
            // 멱등성 키가 없으면 같은 예약이 올 때마다 새 예약이 된다.
            throw new IllegalArgumentException("채널 예약번호는 필수입니다. 멱등성 키로 쓰입니다.");
        }
        if (adults < 0 || children < 0) {
            throw new IllegalArgumentException("인원은 음수일 수 없습니다: " + adults + "/" + children);
        }
        supersedes = supersedes == null ? java.util.Set.of() : java.util.Set.copyOf(supersedes);
    }

    /** 기간으로 묶는다. booking 안에서만 쓰인다. */
    StayPeriod period() {
        return new StayPeriod(checkIn, checkOut);
    }
}
