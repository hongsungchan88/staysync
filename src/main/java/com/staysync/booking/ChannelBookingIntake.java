package com.staysync.booking;

/**
 * booking 모듈이 채널 수신을 위해 공개하는 통로.
 *
 * <p>channel 은 {@code booking.domain} 을 보지 않는다. 예약 생성과 재고 조작은 전부
 * 이 인터페이스를 거친다. 의존 방향은 channel → booking 이다.
 *
 * <p>구현은 락 경계이고 실제 쓰기는 {@code ChannelBookingWriter} 가 한 트랜잭션에서
 * 한다. 예약 저장, 재고 변경, 충돌 기록, {@code outbox_event}, {@code audit_log} 가
 * 함께 성공하거나 함께 실패한다.
 */
public interface ChannelBookingIntake {

    /**
     * 채널 예약 한 건을 받아들인다. 계획서 13.2 의 판단 순서를 따른다.
     *
     * <p><b>재고가 모자라도 거절하지 않는다.</b> OTA 에서 이미 성사된 예약이라 거절하면
     * 게스트와 플랫폼 양쪽에서 문제가 된다. 받아들이고 충돌로 기록해 사람이 푼다.
     */
    ChannelBookingResult ingest(ChannelBookingCommand command);

    /**
     * <b>스냅샷 채널</b>의 취소 처리. 발행물에 없는 예약을 취소한다.
     *
     * <p>iCal 에는 취소 통지가 없다. {@code VEVENT} 가 발행물에서 사라지는 것이 곧
     * 취소이므로, 이번에 받은 식별자 전부를 넘기면 나머지를 취소한다.
     *
     * <p><b>{@code Capability.SNAPSHOT_BOOKING} 을 선언한 채널만 부른다.</b> Mock 과
     * Channex 는 취소를 상태로 알려 주고 목록이 전체 스냅샷이라는 보장도 없어서,
     * 여기를 부르면 <b>아직 목록에 안 나타난 예약을 취소하게 된다.</b>
     *
     * <p>대량 소실 방어가 걸린 주기에는 부르지 않는다. 그 판단은 수신부의 몫이다.
     *
     * @param presentChannelBookingIds 이번 발행물에 있던 채널 예약번호 전부
     * @return 실제로 취소한 건수
     */
    int cancelMissing(Long unitId, String channelCode, java.util.Set<String> presentChannelBookingIds);
}
