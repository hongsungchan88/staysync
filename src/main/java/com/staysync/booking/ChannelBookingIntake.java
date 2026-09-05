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
}
