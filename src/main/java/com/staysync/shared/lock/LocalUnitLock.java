package com.staysync.shared.lock;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * JVM 안에서만 유효한 재고 락. 단일 인스턴스 배포를 전제로 한다.
 *
 * <p>인스턴스가 여러 개가 되면 이 구현으로는 부족하다. 그때는 같은
 * {@link UnitLock} 인터페이스를 구현한 Redis 버전으로 갈아끼우면 되고,
 * 호출하는 쪽 코드는 바뀌지 않는다.
 */
@Component
public class LocalUnitLock implements UnitLock {

    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T runExclusively(Long unitId, Duration waitFor, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(unitId, id -> new ReentrantLock(true));

        boolean acquired;
        try {
            acquired = lock.tryLock(waitFor.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("재고 락 대기 중 인터럽트가 발생했습니다. unitId=" + unitId);
        }
        if (!acquired) {
            throw new LockAcquisitionException("재고 락 획득에 실패했습니다. unitId=" + unitId);
        }

        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
