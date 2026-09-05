package com.staysync.booking;

import com.staysync.shared.lock.UnitLock;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 채널 예약 수신의 <b>락 경계</b>. {@link ChannelBookingIntake} 의 구현이다.
 *
 * <p>{@link BookingService} 와 같은 모양이다 — 락을 먼저 잡고 그 안에서 트랜잭션을
 * 연다. 순서가 반대면 트랜잭션이 열린 채 락을 기다려 커넥션 풀이 마른다. 같은 클래스
 * 안에서 {@code @Transactional} 메서드를 부르면 프록시를 거치지 않는다는 문제도 같다.
 *
 * <p><b>채널 수신도 판매 단위 락을 거친다.</b> 거치지 않으면 방어 4계층 가운데 1계층을
 * 우회하게 되고, 시뮬레이터의 동시 예약 다발이 정확히 그 구멍을 노린다.
 */
@Service
public class ChannelBookingIngestService implements ChannelBookingIntake {

    /** {@code BookingService} 와 같은 값. 채널 수신만 더 오래 기다릴 이유가 없다. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(3);

    private final UnitLock unitLock;
    private final ChannelBookingWriter writer;

    ChannelBookingIngestService(UnitLock unitLock, ChannelBookingWriter writer) {
        this.unitLock = unitLock;
        this.writer = writer;
    }

    @Override
    public ChannelBookingResult ingest(ChannelBookingCommand command) {
        return unitLock.runExclusively(command.unitId(), LOCK_WAIT, () -> writer.ingest(command));
    }

    /**
     * 스냅샷 채널의 취소. <b>목록을 락 안에서 읽는다.</b> 락 밖에서 읽으면 읽는 사이에
     * 들어온 새 예약이 "발행물에 없다"로 판정돼 방금 받은 예약이 취소된다.
     */
    @Override
    public int cancelMissing(Long unitId, String channelCode, Set<String> presentChannelBookingIds) {
        return unitLock.runExclusively(unitId, LOCK_WAIT,
                () -> writer.cancelMissing(unitId, channelCode, presentChannelBookingIds));
    }
}
