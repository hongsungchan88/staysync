package com.staysync.booking;

import com.staysync.booking.domain.OverbookingConflict;
import com.staysync.booking.domain.Reservation;
import com.staysync.property.OwnedResources;
import com.staysync.property.UnitCatalog;
import com.staysync.property.UnitNotFoundException;
import com.staysync.shared.lock.UnitLock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 중복예약 충돌의 해소. 계획서 7.4 이고 방어 4계층의 마지막 자리다.
 *
 * <p>1~3계층은 막는 장치이고 여기는 <b>막을 수 없었던 것을 사람이 푸는</b> 자리다.
 * iCal 은 최대 2시간 지연되므로 이미 팔린 방을 우리가 모르는 구간이 구조적으로 있다.
 *
 * <p>해소 방법 넷 가운데 <b>업그레이드 배정만 재고를 움직인다.</b> 그래서 이 클래스가
 * 락 경계다.
 *
 * <pre>
 *   UPGRADED  판매 단위를 옮긴다  → 락 둘. 옛 단위와 새 단위
 *   RELOCATED 인근 제휴 숙소 안내 → 우리 재고 밖이다. 기록만
 *   CANCELLED 취소와 보상        → 락 하나. 취소가 재고를 되돌린다
 *   ABSORBED  관리자 허용        → 재고를 그대로 둔다. 기록만
 * </pre>
 *
 * <p><b>여기서 락이 두 개가 된다.</b> ADR 0002 결과 절이 예고한 자리다. 요청한
 * 순서대로 잡으면 A→B 를 옮기는 요청과 B→A 를 옮기는 요청이 맞물리는 순간
 * 교착이 난다. 평소에는 아무 일도 없고 두 요청이 겹치는 그 순간에만 멈춘다.
 * <b>판매 단위 식별자 오름차순으로 잡는다.</b>
 *
 * <p>게스트 안내 메시지 초안은 만들지 않는다. 계획서 7.4 가 9장 AI 기능에 맡겼고
 * 그건 P5 다. 여기는 방법을 고르고 기록하는 데까지다.
 *
 * <p><b>클래스에 {@code @Transactional} 을 붙이지 않는다.</b> 여기는 락 경계이고
 * 트랜잭션은 {@link ConflictWriter} 가 연다({@code ChannelBookingIngestService} 와 같은
 * 모양이다). 클래스에 {@code readOnly = true} 를 걸면 해소 경로가 그 읽기 전용
 * 트랜잭션에 합류해 <b>재고 갱신도 감사 기록도 통째로 거부된다.</b>
 */
@Service
public class ConflictResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ConflictResolutionService.class);

    /** {@code BookingService} 와 같은 값. 충돌 해소만 더 오래 기다릴 이유가 없다. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(3);

    private final OverbookingConflictRepository conflicts;
    private final ReservationRepository reservations;
    private final ConflictWriter writer;
    private final OwnedResources owned;
    private final UnitCatalog unitCatalog;
    private final UnitLock unitLock;

    ConflictResolutionService(OverbookingConflictRepository conflicts,
                              ReservationRepository reservations,
                              ConflictWriter writer,
                              OwnedResources owned,
                              UnitCatalog unitCatalog,
                              UnitLock unitLock) {
        this.conflicts = conflicts;
        this.reservations = reservations;
        this.writer = writer;
        this.owned = owned;
        this.unitCatalog = unitCatalog;
        this.unitLock = unitLock;
    }

    /**
     * 미해소 충돌 목록. 화면이 이걸 그린다.
     *
     * <p>충돌한 예약을 함께 담는다. {@code reservation_ids} 만으로는 운영자가 무엇과
     * 무엇이 부딪혔는지 알 수 없고, 화면이 예약을 하나씩 다시 조회하면 목록 하나에
     * 요청이 수십 개가 된다.
     */
    @Transactional(readOnly = true)
    public List<ConflictView> listOpen(Long orgId) {
        List<Long> propertyIds = owned.propertyIdsOf(orgId);
        if (propertyIds.isEmpty()) {
            return List.of();
        }
        List<ConflictView> views = new ArrayList<>();
        for (OverbookingConflict conflict : conflicts.findOpenOf(propertyIds)) {
            views.add(new ConflictView(conflict,
                    reservations.findAllById(conflict.getReservationIds())));
        }
        return views;
    }

    /** 충돌 하나와 그 자리에서 부딪힌 예약들. */
    public record ConflictView(OverbookingConflict conflict, List<Reservation> reservations) {
    }

    /**
     * 충돌을 해소한다.
     *
     * @param targetUnitId {@code UPGRADED} 일 때만 쓴다. 예약을 옮길 판매 단위
     * @param reservationId 옮기거나 취소할 예약. 그 자리에 예약이 둘 이상이라
     *                      운영자가 어느 쪽을 움직일지 골라야 한다
     */
    public OverbookingConflict resolve(Long conflictId, Long orgId, Long actorId,
                                       String resolution, Long reservationId,
                                       Long targetUnitId, String memo) {
        OverbookingConflict conflict = load(conflictId, orgId);

        if (!OverbookingConflict.UPGRADED.equals(resolution)) {
            // 재고를 옮기지 않는다. 취소는 ReservationWriter 가 자기 락을 잡는다.
            return writer.resolveWithoutMove(
                    conflictId, resolution, actorId, reservationId, memo);
        }
        return upgrade(conflict, actorId, reservationId, targetUnitId, memo);
    }

    /**
     * 업그레이드 배정. <b>락을 둘 쥔다.</b>
     *
     * <p>옛 단위와 새 단위를 <b>판매 단위 식별자 오름차순</b>으로 잡는다. 요청한
     * 순서(옛 것 먼저)로 잡으면, A→B 를 옮기는 요청과 B→A 를 옮기는 요청이 맞물리는
     * 순간 서로가 서로를 기다린다. ADR 0002 결과 절이 "락이 둘이 되는 곳이 생기면
     * 순서를 정하라"고 적어 둔 자리가 여기다.
     *
     * <p>락을 먼저 잡고 그 안에서 트랜잭션을 연다. 순서가 반대면 트랜잭션이 열린 채
     * 락을 기다려 커넥션 풀이 마른다.
     */
    private OverbookingConflict upgrade(OverbookingConflict conflict, Long actorId,
                                        Long reservationId, Long targetUnitId, String memo) {
        if (targetUnitId == null) {
            throw new InvalidConflictResolutionException("업그레이드 배정에는 옮길 판매 단위가 필요합니다.");
        }
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        requireSameProperty(conflict, targetUnitId);

        List<Long> order = lockOrder(reservation.getUnitId(), targetUnitId);
        log.info("업그레이드 배정으로 충돌을 해소한다. conflictId={} reservationId={} {} → {} 락순서={}",
                conflict.getId(), reservationId, reservation.getUnitId(), targetUnitId, order);

        return runExclusively(order, () -> writer.resolveWithUpgrade(
                conflict.getId(), actorId, reservationId, targetUnitId, memo));
    }

    /**
     * 판매 단위 식별자 오름차순. 같은 단위면 하나만 남는다.
     *
     * <p>순수 함수로 떼어 둔 이유는 <b>이것이 교착을 막는 유일한 장치</b>라서다.
     * 락 순서가 틀려도 평소에는 아무 일이 없고, 두 요청이 맞물리는 순간에만 멈춘다.
     * 그런 성질은 동시성 테스트로만 확인하면 "가끔 통과하는 테스트"가 되므로
     * 순서 자체를 직접 확인할 수 있게 둔다.
     */
    static List<Long> lockOrder(Long a, Long b) {
        Set<Long> unique = new LinkedHashSet<>(List.of(a, b));
        return unique.stream().sorted().toList();
    }

    /** 순서대로 겹쳐 잡는다. {@code ReentrantLock} 이라 안쪽에서 다시 잡아도 재진입이다. */
    private <T> T runExclusively(List<Long> unitIds, java.util.function.Supplier<T> action) {
        if (unitIds.size() == 1) {
            return unitLock.runExclusively(unitIds.get(0), LOCK_WAIT, action);
        }
        return unitLock.runExclusively(unitIds.get(0), LOCK_WAIT,
                () -> unitLock.runExclusively(unitIds.get(1), LOCK_WAIT, action));
    }

    private void requireSameProperty(OverbookingConflict conflict, Long targetUnitId) {
        boolean belongs = unitCatalog.summariesOf(conflict.getPropertyId()).stream()
                .anyMatch(unit -> unit.id().equals(targetUnitId));
        if (!belongs) {
            // 다른 숙소의 방으로 옮기는 것은 업그레이드가 아니라 대체 숙소 안내다.
            throw new UnitNotFoundException(targetUnitId);
        }
    }

    private OverbookingConflict load(Long conflictId, Long orgId) {
        OverbookingConflict conflict = conflicts.findById(conflictId)
                .orElseThrow(() -> new ConflictNotFoundException(conflictId));
        if (!owned.ownsProperty(conflict.getPropertyId(), orgId)) {
            throw new ConflictNotFoundException(conflictId);
        }
        return conflict;
    }
}
