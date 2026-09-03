package com.staysync.booking;

import com.staysync.booking.domain.InventoryLedger;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.property.UnitCatalog;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 원장에 실제로 쓰는 트랜잭션 경계.
 *
 * <p>{@link InventoryService} 와 분리한 이유가 있다. 같은 클래스 안에서
 * {@code @Transactional} 메서드를 호출하면 스프링 프록시를 거치지 않아 트랜잭션이
 * 걸리지 않는다. 락을 잡는 쪽과 트랜잭션을 여는 쪽을 다른 빈으로 두어야 한다.
 */
@Component
class InventoryLedgerWriter {

    private final InventoryLedgerRepository ledgerRepo;
    private final UnitCatalog unitCatalog;

    InventoryLedgerWriter(InventoryLedgerRepository ledgerRepo, UnitCatalog unitCatalog) {
        this.ledgerRepo = ledgerRepo;
        this.unitCatalog = unitCatalog;
    }

    @Transactional
    void apply(Long unitId, StayPeriod period, int units, LedgerAction action) {
        List<LocalDate> nights = period.nightDates();          // 오름차순이 보장된다
        List<InventoryLedger> rows = loadOrCreate(unitId, nights);

        for (InventoryLedger row : rows) {
            switch (action) {
                case BOOK -> row.book(units);
                case HOLD -> row.hold(units);
                case PROMOTE -> row.promoteHold(units);
                case RELEASE_HOLD -> row.releaseHold(units);
                case RELEASE -> row.release(units);
                case FORCE_BOOK -> row.forceBook(units);
            }
        }
        ledgerRepo.saveAll(rows);
    }

    /**
     * 판매중지를 켜고 끈다.
     *
     * <p>수량을 건드리지 않지만 원장 행을 바꾸는 일이라 {@link InventoryService} 를
     * 거친다. 락 순서(날짜 오름차순)와 {@code FOR UPDATE} 를 그대로 타야 예약 처리와
     * 같은 행을 두고 경쟁할 때 어긋나지 않는다.
     *
     * <p>날짜가 연속이 아닐 수 있다. 요일 필터가 걸린 일괄 편집이 그렇다. 그래서
     * {@code StayPeriod} 가 아니라 날짜 목록을 받는다.
     */
    @Transactional
    void applyStopSell(Long unitId, List<LocalDate> dates, boolean stopSell) {
        List<LocalDate> ascending = dates.stream().sorted().distinct().toList();
        List<InventoryLedger> rows = loadOrCreate(unitId, ascending);
        for (InventoryLedger row : rows) {
            row.changeStopSell(stopSell);
        }
        ledgerRepo.saveAll(rows);
    }

    /**
     * 재고 행을 배타 잠금과 함께 읽는다. 없는 날짜는 그때 만든다.
     *
     * <p>원장을 미리 채워두지 않는 이유는 판매 단위 하나당 1년치 365행 가운데
     * 대부분이 쓰이지 않기 때문이다.
     */
    private List<InventoryLedger> loadOrCreate(Long unitId, List<LocalDate> nights) {
        List<InventoryLedger> rows = ledgerRepo.findForUpdate(unitId, nights);
        if (rows.size() == nights.size()) {
            return rows;
        }

        Set<LocalDate> found = new HashSet<>();
        rows.forEach(row -> found.add(row.getStayDate()));

        short capacity = unitCatalog.totalUnitsOf(unitId);
        List<InventoryLedger> created = new ArrayList<>();
        for (LocalDate night : nights) {
            if (!found.contains(night)) {
                created.add(new InventoryLedger(unitId, night, capacity));
            }
        }
        ledgerRepo.saveAllAndFlush(created);

        return ledgerRepo.findForUpdate(unitId, nights);
    }
}
