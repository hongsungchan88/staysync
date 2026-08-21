package com.staysync.shared.lock;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 판매 단위(Unit) 재고를 배타적으로 다루기 위한 락.
 *
 * <p>재고 방어 계층의 1계층에 해당한다. 2계층은 데이터베이스의
 * {@code SELECT ... FOR UPDATE}, 3계층은 {@code CHECK} 제약이다.
 *
 * <p>단일 인스턴스 배포에서는 {@link LocalUnitLock} 으로 충분하다.
 * 인스턴스를 여러 개 띄우게 되면 Redis 기반 구현으로 교체한다(P3 예정).
 */
public interface UnitLock {

    /**
     * 지정한 판매 단위에 대해 배타적으로 작업을 수행한다.
     *
     * @param unitId  잠글 판매 단위 식별자
     * @param waitFor 락 획득을 기다릴 최대 시간
     * @param action  락을 쥔 상태로 수행할 작업
     * @throws LockAcquisitionException 제한 시간 안에 락을 얻지 못한 경우
     */
    <T> T runExclusively(Long unitId, Duration waitFor, Supplier<T> action);

    default void runExclusively(Long unitId, Duration waitFor, Runnable action) {
        runExclusively(unitId, waitFor, () -> {
            action.run();
            return null;
        });
    }
}
