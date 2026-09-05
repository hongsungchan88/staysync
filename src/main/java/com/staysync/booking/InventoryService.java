package com.staysync.booking;

import com.staysync.booking.domain.InsufficientInventoryException;
import com.staysync.booking.domain.InventoryLedger;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.shared.lock.UnitLock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고를 원자적으로 다루는 서비스.
 *
 * <p>중복예약 방어는 네 계층으로 구성된다.
 * <ol>
 *   <li>{@link UnitLock} — 애플리케이션 수준의 배타 락</li>
 *   <li>{@code SELECT ... FOR UPDATE} — 데이터베이스 행 잠금, 날짜 오름차순 획득</li>
 *   <li>{@code CHECK} 제약 — 로직 결함이 통과해도 데이터베이스가 거부</li>
 *   <li>충돌 감지 — iCal 지연처럼 원천 차단이 불가능한 경우의 사후 처리</li>
 * </ol>
 *
 * <p>락을 먼저 잡고 그 안에서 트랜잭션을 연다. 순서가 반대면 트랜잭션이 열린 채로
 * 락을 기다리게 되어 커넥션 풀이 마른다.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private static final Duration LOCK_WAIT = Duration.ofSeconds(3);

    private final InventoryLedgerRepository ledgerRepo;
    private final InventoryLedgerWriter writer;
    private final UnitLock unitLock;

    public InventoryService(InventoryLedgerRepository ledgerRepo,
                            InventoryLedgerWriter writer,
                            UnitLock unitLock) {
        this.ledgerRepo = ledgerRepo;
        this.writer = writer;
        this.unitLock = unitLock;
    }

    /**
     * 확정 예약으로 재고를 차감한다.
     *
     * @throws InsufficientInventoryException 하루라도 재고가 모자라면 전체를 실패시킨다
     */
    public void reserve(Long unitId, StayPeriod period, int units) {
        run(unitId, period, units, LedgerAction.BOOK);
    }

    /** 결제 대기 상태로 임시 점유한다. */
    public void hold(Long unitId, StayPeriod period, int units) {
        run(unitId, period, units, LedgerAction.HOLD);
    }

    /** 임시 점유를 확정으로 승격한다. */
    public void promoteHold(Long unitId, StayPeriod period, int units) {
        run(unitId, period, units, LedgerAction.PROMOTE);
    }

    /** 임시 점유를 해제한다. 결제 실패나 시간 만료 시 호출한다. */
    public void releaseHold(Long unitId, StayPeriod period, int units) {
        run(unitId, period, units, LedgerAction.RELEASE_HOLD);
    }

    /** 확정 예약을 취소해 재고를 되돌린다. */
    public void release(Long unitId, StayPeriod period, int units) {
        run(unitId, period, units, LedgerAction.RELEASE);
    }

    /**
     * 재고 한도를 넘겨 강제로 기록한다.
     *
     * <p>OTA 에서 이미 성사된 예약은 거절할 수 없다. 거절하면 게스트와 플랫폼 양쪽에서
     * 문제가 된다. 받아들이고 충돌로 승격시켜 운영자가 업그레이드, 대체 숙소, 취소
     * 중에서 선택하게 한다.
     */
    public void forceBook(Long unitId, StayPeriod period, int units) {
        run(unitId, period, units, LedgerAction.FORCE_BOOK);
        log.warn("재고 한도를 넘겨 예약을 기록했다. unitId={} {}~{}",
                unitId, period.checkIn(), period.checkOut());
    }

    /**
     * 판매중지를 켜고 끈다. 요금·제약 일괄 편집이 부른다.
     *
     * <p>수량을 깎지는 않지만 원장 행을 바꾸는 일이므로 여기를 거친다. 프론트나
     * 조립부가 원장에 직접 쓰면 락과 {@code FOR UPDATE} 를 우회하게 되고, 방어 계층에
     * 예외가 하나 생기면 다음에도 생긴다.
     *
     * <p>부르는 쪽이 이미 같은 판매 단위의 락을 쥐고 있어도 된다.
     * {@code ReentrantLock} 이라 같은 스레드에서는 재진입이 되며, 실제로 일괄 편집이
     * 그렇게 부른다.
     */
    public void changeStopSell(Long unitId, java.util.List<java.time.LocalDate> dates,
                               boolean stopSell) {
        if (dates.isEmpty()) {
            return;
        }
        unitLock.runExclusively(unitId, LOCK_WAIT,
                () -> writer.applyStopSell(unitId, dates, stopSell));
    }

    /** 지정 기간에 판매 가능한 최소 수량. 0 이면 그 기간은 팔 수 없다. */
    @Transactional(readOnly = true)
    public int availableFor(Long unitId, StayPeriod period) {
        List<InventoryLedger> rows = ledgerRepo.findGrid(
                List.of(unitId), period.checkIn(), period.checkOut().minusDays(1));

        if (rows.size() < period.nights()) {
            // 원장이 아직 없는 날짜는 기본 수량으로 팔 수 있다고 본다.
            // 실제 차감은 예약 시점에 원장을 만들면서 검증한다.
            return rows.stream()
                    .mapToInt(this::sellable)
                    .min()
                    .orElse(Integer.MAX_VALUE) == 0 ? 0 : Integer.MAX_VALUE;
        }
        return rows.stream().mapToInt(this::sellable).min().orElse(0);
    }

    /**
     * 날짜별 판매 가능 수량. 채널 전파가 쓴다.
     *
     * <p>{@link #availableFor} 는 기간의 최솟값 하나를 돌려준다. 채널에 보낼 때는 그걸로
     * 부족하다 — 최솟값을 전 기간에 실으면 여유 있는 날까지 막혀 <b>팔 수 있는 방을
     * 못 판다.</b> 그래서 날짜마다 따로 돌려준다.
     *
     * <p>원장 행이 없는 날은 아직 아무도 예약하지 않은 날이다. 캘린더 조립부와 같은
     * 규칙으로 {@code totalUnits} 를 채운다 — 다르면 화면에 보이는 값과 채널에 나가는
     * 값이 갈린다.
     *
     * @param totalUnits 원장 행이 없는 날에 쓸 기본 수량. 판매 단위의 판매 수량이다
     */
    @Transactional(readOnly = true)
    public List<DailyAvailability> availabilityByDate(Long unitId, LocalDate from, LocalDate to,
                                                      int totalUnits) {
        Map<LocalDate, InventoryLedger> rows = new HashMap<>();
        for (InventoryLedger row : ledgerRepo.findGrid(List.of(unitId), from, to)) {
            rows.put(row.getStayDate(), row);
        }

        List<DailyAvailability> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            InventoryLedger row = rows.get(date);
            result.add(new DailyAvailability(date,
                    row == null ? totalUnits : row.available(),
                    row != null && row.isStopSell()));
        }
        return result;
    }

    private int sellable(InventoryLedger row) {
        return row.isStopSell() ? 0 : row.available();
    }

    private void run(Long unitId, StayPeriod period, int units, LedgerAction action) {
        unitLock.runExclusively(unitId, LOCK_WAIT, () -> {
            writer.apply(unitId, period, units, action);
        });
    }
}
