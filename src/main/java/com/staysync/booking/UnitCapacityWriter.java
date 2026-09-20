package com.staysync.booking;

import com.staysync.booking.domain.InventoryLedger;
import com.staysync.property.PropertyService;
import com.staysync.property.UnitSummary;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수량 변경의 <b>트랜잭션 경계</b>. 락은 {@link UnitCapacityService} 가 바깥에서 잡는다.
 *
 * <p>원장 행 갱신과 {@code unit.total_units} 갱신이 <b>한 트랜잭션</b>이다. 하나만 반영되면
 * 화면의 수량과 실제로 팔 수 있는 수량이 갈리고, 둘 다 화면에서는 정상으로 보인다.
 *
 * <p><b>오늘(Asia/Seoul) 이후의 행만 바꾼다.</b> 지난 행은 그날 팔 수 있었던 수량의 기록이다 —
 * 캘린더의 지난 칸은 그대로고 ARI·위젯은 과거를 보지 않는다. 리포트의 분모는 원장이 아니라
 * {@code unit.total_units} 를 읽으므로 과거 점유율은 어차피 새 수량으로 다시 계산된다(8.1).
 *
 * <p><b>줄이기는 먼저 조회해 분기한다.</b> 막는 날이 있으면 아무것도 바꾸지 않고 그 날짜들을
 * 돌려준다. 강행해 초과 상태로 만들지 않는다.
 */
@Component
class UnitCapacityWriter {

    private static final Logger log = LoggerFactory.getLogger(UnitCapacityWriter.class);

    private final InventoryLedgerRepository ledgerRepo;
    private final PropertyService propertyService;

    UnitCapacityWriter(InventoryLedgerRepository ledgerRepo, PropertyService propertyService) {
        this.ledgerRepo = ledgerRepo;
        this.propertyService = propertyService;
    }

    @Transactional
    UnitSummary change(Long unitId, Long orgId, short totalUnits) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        List<InventoryLedger> rows = ledgerRepo.findFromForUpdate(unitId, today);

        List<LocalDate> blocking = rows.stream()
                .filter(row -> row.getBookedUnits() + row.getHeldUnits() > totalUnits)
                .map(InventoryLedger::getStayDate)
                .toList();
        if (!blocking.isEmpty()) {
            throw new CapacityBelowBookingsException(unitId, totalUnits, blocking);
        }

        for (InventoryLedger row : rows) {
            row.changeTotalUnits(totalUnits);
        }
        ledgerRepo.saveAll(rows);
        UnitSummary unit = propertyService.changeUnitCapacity(unitId, orgId, totalUnits);
        log.info("판매 수량을 바꿨다. unitId={} total={} 원장 {}행(오늘 이후)", unitId, totalUnits, rows.size());
        return unit;
    }
}
