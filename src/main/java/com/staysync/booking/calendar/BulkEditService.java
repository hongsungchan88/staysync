package com.staysync.booking.calendar;

import com.staysync.shared.lock.UnitLock;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 요금·제약 일괄 편집의 <b>락 경계</b>.
 *
 * <p>{@code BookingService} 와 같은 역할이다. 락만 잡고 실제 쓰기는
 * {@link BulkEditWriter} 에 맡긴다. 락을 먼저 잡고 그 안에서 트랜잭션을 열어야
 * 트랜잭션이 열린 채 락을 기다려 커넥션 풀이 마르는 일이 없다.
 *
 * <p><b>판매 단위 락을 여럿 잡는다.</b> 일괄 편집은 여러 단위를 한 번에 바꾸기 때문이다.
 * 여기가 교착 상태가 나기 쉬운 자리이고, 막는 방법은 재고 원장의 날짜 락과 같다 —
 * <b>식별자 오름차순으로 고정한다.</b> 두 편집이 서로 다른 순서로 잡으면 서로가 쥔
 * 락을 기다린다.
 */
@Service
public class BulkEditService {

    /**
     * 락 대기 시간.
     *
     * <p>{@code BookingService} 의 3초보다 길다. 여러 단위를 차례로 잡는 동안 앞의 것을
     * 쥔 채 뒤를 기다리므로, 예약 한 건보다 대기가 누적된다. 그렇다고 무한정 기다리면
     * 화면이 멈춘 것처럼 보인다.
     */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(10);

    private final UnitLock unitLock;
    private final BulkEditWriter writer;

    BulkEditService(UnitLock unitLock, BulkEditWriter writer) {
        this.unitLock = unitLock;
        this.writer = writer;
    }

    /**
     * 편집한다. {@code dryRun} 이면 무엇이 바뀔지만 센다.
     *
     * <p>미리보기도 락을 거친다. 세는 동안 다른 요청이 같은 셀을 바꾸면 미리보기가
     * 실제와 어긋나기 때문이다. 미리보기는 아무것도 쓰지 않지만 읽는 것은 같다.
     */
    public BulkEdit.Result edit(Long propertyId, BulkEdit.Request request) {
        List<Long> ordered = request.unitIds().stream().sorted().distinct().toList();
        return withLocks(ordered, 0, () -> writer.apply(propertyId, request));
    }

    /**
     * 목록의 락을 순서대로 겹쳐 잡는다.
     *
     * <p>{@code UnitLock} 이 한 번에 하나만 받으므로 재귀로 겹친다. 목록이 오름차순이라
     * 어느 스레드든 같은 순서로 잡고, 그래서 교착 상태가 생기지 않는다.
     */
    private <T> T withLocks(List<Long> unitIds, int index, Supplier<T> action) {
        if (index == unitIds.size()) {
            return action.get();
        }
        return unitLock.runExclusively(unitIds.get(index), LOCK_WAIT,
                () -> withLocks(unitIds, index + 1, action));
    }
}
