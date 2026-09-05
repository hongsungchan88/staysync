package com.staysync.booking;

import com.staysync.booking.domain.OverbookingConflict;
import com.staysync.shared.audit.AuditRecorder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 충돌 해소의 <b>트랜잭션 경계</b>. 락은 {@link ConflictResolutionService} 가 바깥에서 잡는다.
 *
 * <p>둘을 나눈 이유는 이 프로젝트에서 반복된 그것이다 — 같은 클래스 안에서
 * {@code @Transactional} 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않고,
 * 락보다 트랜잭션을 먼저 열면 커넥션 풀이 마른다.
 *
 * <p>재고 이동과 충돌 기록이 <b>한 트랜잭션</b>이다. 판별 기준은 그대로다 — 바깥이
 * 롤백될 때 이 쓰기가 남아야 하는가. 방은 옮겼는데 충돌이 {@code OPEN} 으로 남으면
 * 운영자가 같은 건을 한 번 더 처리하고, 그때 방이 또 옮겨진다.
 */
@Component
class ConflictWriter {

    private static final String ENTITY = "OVERBOOKING_CONFLICT";

    private final OverbookingConflictRepository conflicts;
    private final ReservationWriter reservationWriter;
    private final AuditRecorder audit;

    ConflictWriter(OverbookingConflictRepository conflicts,
                   ReservationWriter reservationWriter,
                   AuditRecorder audit) {
        this.conflicts = conflicts;
        this.reservationWriter = reservationWriter;
        this.audit = audit;
    }

    /**
     * 업그레이드 배정. 예약을 다른 판매 단위로 옮기고 해소로 기록한다.
     *
     * <p><b>해소 기록을 먼저 한다.</b> 이미 해소된 충돌이면 여기서 예외가 나고, 그때
     * 재고는 아직 움직이지 않았다. 순서가 반대면 두 번째 요청이 방을 옮긴 뒤에야
     * 거절돼 롤백에 기대게 된다 — 롤백이 되긴 하지만, 되돌릴 것이 없는 편이 낫다.
     */
    @Transactional
    OverbookingConflict resolveWithUpgrade(Long conflictId, Long actorId,
                                           Long reservationId, Long targetUnitId, String memo) {
        OverbookingConflict conflict = conflicts.findById(conflictId).orElseThrow();
        conflict.resolve(OverbookingConflict.UPGRADED, actorId, memo);

        reservationWriter.moveToUnit(reservationId, targetUnitId);

        audit.record(ENTITY, conflictId, "CONFLICT_RESOLVE", null,
                snapshot(OverbookingConflict.UPGRADED, reservationId, targetUnitId, memo));
        return conflict;
    }

    /**
     * 재고를 옮기지 않는 해소 셋.
     *
     * <p>{@code CANCELLED} 만 예약을 건드린다. 취소는 {@code ReservationWriter} 가
     * 재고 반납까지 한 트랜잭션으로 처리하므로 여기서 원장을 따로 만지지 않는다.
     */
    @Transactional
    OverbookingConflict resolveWithoutMove(Long conflictId, String resolution, Long actorId,
                                           Long reservationId, String memo) {
        OverbookingConflict conflict = conflicts.findById(conflictId).orElseThrow();
        if (!isKnown(resolution)) {
            throw new InvalidConflictResolutionException(
                    "해소 방법은 UPGRADED·RELOCATED·CANCELLED·ABSORBED 중 하나여야 합니다. 받은 값=" + resolution);
        }
        conflict.resolve(resolution, actorId, memo);

        if (OverbookingConflict.CANCELLED.equals(resolution)) {
            if (reservationId == null) {
                throw new InvalidConflictResolutionException("취소하려면 어느 예약인지 골라야 합니다.");
            }
            reservationWriter.cancel(reservationId);
        }

        audit.record(ENTITY, conflictId, "CONFLICT_RESOLVE", null,
                snapshot(resolution, reservationId, null, memo));
        return conflict;
    }

    private static boolean isKnown(String resolution) {
        return OverbookingConflict.UPGRADED.equals(resolution)
                || OverbookingConflict.RELOCATED.equals(resolution)
                || OverbookingConflict.CANCELLED.equals(resolution)
                || OverbookingConflict.ABSORBED.equals(resolution);
    }

    /** 감사 기록의 값. 게스트 정보는 담지 않는다 — 식별자만이다(ADR 0007 의 선). */
    private static Map<String, Object> snapshot(String resolution, Long reservationId,
                                                Long targetUnitId, String memo) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("resolution", resolution);
        after.put("reservationId", reservationId);
        after.put("targetUnitId", targetUnitId);
        after.put("memo", memo);
        return after;
    }
}
