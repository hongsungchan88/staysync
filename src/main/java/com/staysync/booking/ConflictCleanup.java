package com.staysync.booking;

import com.staysync.booking.domain.OverbookingConflict;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.ReservationStatus;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.property.UnitCatalog;
import com.staysync.shared.audit.ActorKind;
import com.staysync.shared.audit.AuditRecorder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 재고가 반납된 뒤 OPEN 충돌을 뒷정리한다(작업지시-18 9절 F).
 *
 * <p>충돌에 걸린 예약이 취소·만료·이동되어 그날 그 판매 단위의 활성 예약이 실제 수량
 * 이하가 되면 카드를 <b>자동으로 닫는다.</b> 아직 초과면 예약 목록만 갱신한다 — 셋이
 * 겹쳤다가 하나 취소돼 둘이 남으면 카드는 열린 채로 둘을 담는다.
 *
 * <p>판정 자료는 {@code ChannelBookingWriter.overlappingIds} 와 같다 — 그날 겹치는 활성
 * 예약. 원장의 {@code overbooked_units} 도 같은 답을 내지만 카드에 담을 예약 식별자가
 * 필요해 예약 쪽을 읽는다.
 *
 * <p><b>부르는 자리는 반납 경로 다섯의 끝이다</b> — 취소·만료·기간 변경·단위 이동·채널
 * 수정. 원장 쓰기 안에 걸지 않는 이유는 순서다: {@code changeStay} 는 원장을 먼저 옮기고
 * 엔티티의 기간을 나중에 바꾸므로, 그 사이에 세면 옮겨 간 예약이 옛 날짜에 아직 있다.
 *
 * <p>자동으로 닫힌 것은 {@code resolution = AUTO_CLOSED}, 해소자 없음으로 남는다.
 * 사람이 고를 수 있는 넷과 갈린다. 감사 기록은 사람이 닫을 때와 같은 모양이고 행위자는
 * SYSTEM 이다 — 사용자의 취소 요청 안에서 돌아도 닫기로 한 것은 시스템이다.
 */
@Component
class ConflictCleanup {

    private static final Logger log = LoggerFactory.getLogger(ConflictCleanup.class);

    private static final List<ReservationStatus> ACTIVE = List.of(
            ReservationStatus.HOLD, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN);

    private final OverbookingConflictRepository conflicts;
    private final ReservationRepository reservations;
    private final UnitCatalog unitCatalog;
    private final AuditRecorder audit;

    ConflictCleanup(OverbookingConflictRepository conflicts,
                    ReservationRepository reservations,
                    UnitCatalog unitCatalog,
                    AuditRecorder audit) {
        this.conflicts = conflicts;
        this.reservations = reservations;
        this.unitCatalog = unitCatalog;
        this.audit = audit;
    }

    /**
     * 이 판매 단위의 이 기간에서 재고가 반납된 뒤 부른다. 부르는 쪽의 트랜잭션에 합류한다.
     *
     * <p>OPEN 충돌이 없는 날은 아무 일도 하지 않는다 — 대부분의 반납이 그렇다.
     */
    void afterRelease(Long propertyId, Long unitId, StayPeriod period) {
        int capacity = unitCatalog.totalUnitsOf(unitId);
        for (LocalDate date : period.nightDates()) {
            OverbookingConflict open = conflicts.findOpenOn(unitId, date).orElse(null);
            if (open == null) {
                continue;
            }
            List<Long> active = activeOn(propertyId, unitId, date);
            if (active.size() <= capacity) {
                String memo = "초과가 풀려 자동으로 닫혔다. 남은 활성 예약 %d / 수량 %d"
                        .formatted(active.size(), capacity);
                open.closeAutomatically(memo);
                audit.recordAs(ActorKind.SYSTEM, null, "OVERBOOKING_CONFLICT", open.getId(),
                        "CONFLICT_RESOLVE", null, snapshot(memo));
                log.info("충돌이 자동으로 닫혔다. conflictId={} unitId={} date={} 남은 예약={}",
                        open.getId(), unitId, date, active);
            } else {
                open.replaceReservationIds(active);
            }
        }
    }

    private List<Long> activeOn(Long propertyId, Long unitId, LocalDate date) {
        List<Long> ids = new ArrayList<>();
        for (Reservation r : reservations.findOverlapping(propertyId, date, date.plusDays(1), ACTIVE)) {
            if (r.getUnitId().equals(unitId)) {
                ids.add(r.getId());
            }
        }
        return ids;
    }

    /** {@code ConflictWriter.snapshot} 과 같은 모양. 사람이 닫은 기록과 나란히 읽힌다. */
    private static Map<String, Object> snapshot(String memo) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("resolution", OverbookingConflict.AUTO_CLOSED);
        after.put("reservationId", null);
        after.put("targetUnitId", null);
        after.put("memo", memo);
        return after;
    }
}
