package com.staysync.shared.outbox;

/**
 * 커밋된 이벤트를 실제로 내보내는 곳. <b>소비자를 붙이는 지점</b>이다.
 *
 * <p>계획서 4.4 는 {@code ChannelSyncWorker}, {@code OpsWorker},
 * {@code MessagingWorker} 를 그리지만 각각 P3, P4, P4 다.
 *
 * <p><b>구현을 빈으로 등록하면 그것으로 끝이다.</b> {@link OutboxRelay} 가 이 타입의
 * 빈을 전부 받아 하나씩 돌린다. P2 9주차의 캘린더 실시간 갱신이 첫 입주자이고,
 * 로그만 남기는 구현이 그 옆에 함께 있다.
 *
 * <p>구현이 예외를 던지면 릴레이가 실패로 기록하고 다음 주기에 다시 시도한다.
 * 그러므로 <b>구현은 실패를 삼키지 말아야 한다.</b> 조용히 성공으로 처리하면
 * 이벤트가 유실된다.
 */
public interface DomainEventPublisher {

    void publish(OutboxEvent event);
}
