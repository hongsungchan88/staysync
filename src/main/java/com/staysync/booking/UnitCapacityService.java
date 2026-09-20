package com.staysync.booking;

import com.staysync.property.PropertyService;
import com.staysync.property.UnitSummary;
import com.staysync.shared.lock.UnitLock;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * 판매 단위의 수량 변경. <b>락 경계.</b> 트랜잭션은 {@link UnitCapacityWriter} 가 연다.
 *
 * <p>수량은 property 의 값이지만 바꾸는 일은 booking 이 든다(작업지시-19 C). 원장 행의
 * {@code total_units} 가 함께 따라가야 하고, 그것은 판매 단위 락 안의 원장 쓰기다 —
 * property 는 booking 을 부를 수 없고(의존 방향), 역방향 포트는 두지 않는다.
 * 예전에는 {@code PropertyService.updateUnit} 이 {@code unit.total_units} 만 바꿔 원장이
 * 옛 수량으로 남았다(작업지시-18 9.4.1).
 *
 * <p>락을 먼저 잡고 그 안에서 트랜잭션을 연다. {@code InventoryService} 와 같은 모양이다.
 */
@Service
public class UnitCapacityService {

    private static final Duration LOCK_WAIT = Duration.ofSeconds(3);

    private final UnitLock unitLock;
    private final UnitCapacityWriter writer;
    private final PropertyService propertyService;

    UnitCapacityService(UnitLock unitLock, UnitCapacityWriter writer, PropertyService propertyService) {
        this.unitLock = unitLock;
        this.writer = writer;
        this.propertyService = propertyService;
    }

    /**
     * @throws CapacityBelowBookingsException 오늘 이후에 {@code booked + held} 가 새 수량을
     *         넘는 날이 하나라도 있으면. 그 날짜들이 예외에 담긴다
     */
    public UnitSummary change(Long unitId, Long orgId, short totalUnits) {
        // 소유 확인이 먼저다. 남의 판매 단위면 락을 잡을 일도 없다.
        propertyService.requireOwnedUnit(unitId, orgId);
        return unitLock.runExclusively(unitId, LOCK_WAIT,
                () -> writer.change(unitId, orgId, totalUnits));
    }
}
