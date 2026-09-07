package com.staysync.ops;

import com.staysync.booking.ReservationBrief;
import com.staysync.booking.ReservationDirectory;
import com.staysync.messaging.InboxNotes;
import com.staysync.ops.domain.OpsTask;
import com.staysync.ops.domain.TaskStatus;
import com.staysync.ops.domain.TaskType;
import com.staysync.property.OwnedResources;
import com.staysync.property.StayTimes;
import com.staysync.property.UnitCatalog;
import com.staysync.property.UnitHousekeeping;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 청소 태스크의 생성·조회·이동. ops 모듈의 바깥 표면이다.
 *
 * <p>모든 화면 진입점이 {@link OwnedResources} 를 거친다. 남의 조직 태스크는 404 다.
 *
 * <p><b>모듈 경계.</b> {@code booking.domain} 도 {@code property.domain} 도 보지 않는다.
 * 다음 체크인은 {@link ReservationDirectory}, 판매 단위 상태는 {@link UnitHousekeeping},
 * 알림은 {@link InboxNotes} 로 간다. 의존 방향은 ops → booking·property·messaging 이다.
 */
@Service
public class OpsTaskService {

    private static final Logger log = LoggerFactory.getLogger(OpsTaskService.class);

    /** 날짜 필터가 없을 때 쓰는 경계. {@code timestamptz} 가 담을 수 있는 범위 안이다. */
    private static final OffsetDateTime 아주_옛날 =
            LocalDate.of(1, 1, 1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    private static final OffsetDateTime 아주_먼_훗날 =
            LocalDate.of(9999, 12, 31).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

    private final OpsTaskRepository tasks;
    private final ReservationDirectory reservations;
    private final UnitCatalog unitCatalog;
    private final UnitHousekeeping housekeeping;
    private final InboxNotes notes;
    private final OwnedResources owned;

    OpsTaskService(OpsTaskRepository tasks, ReservationDirectory reservations,
                   UnitCatalog unitCatalog, UnitHousekeeping housekeeping,
                   InboxNotes notes, OwnedResources owned) {
        this.tasks = tasks;
        this.reservations = reservations;
        this.unitCatalog = unitCatalog;
        this.housekeeping = housekeeping;
        this.notes = notes;
        this.owned = owned;
    }

    // --- 생성 -----------------------------------------------------------------

    /**
     * 체크아웃한 예약에 청소 태스크를 만든다.
     *
     * <p><b>한 트랜잭션이다.</b> 태스크·판매 단위 상태·알림이 함께 성공하거나 함께
     * 사라진다. 태스크만 남고 단위가 깨끗한 것으로 남으면 "청소해야 하는데 깨끗함"이
     * 되고, 그 화면은 정상으로 보인다.
     *
     * <p>거르는 것이 둘이다.
     *
     * <ol>
     *   <li><b>이미 만든 것</b> — Outbox 는 최소 1회 전달이라 같은 체크아웃 이벤트가
     *       두 번 온다. {@code uq_task_reservation} 이 최종 방어선이고 여기 확인이
     *       평소를 맡는다</li>
     *   <li><b>취소된 예약</b> — 체크아웃된 예약이 나중에 취소되는 경우가 있다.
     *       이벤트의 값을 믿지 않고 <b>지금 상태를 다시 읽는다</b></li>
     * </ol>
     *
     * @return 실제로 만들었으면 그 태스크
     */
    @Transactional
    public Optional<OpsTask> createCleaningFor(Long reservationId) {
        Optional<ReservationBrief> found = reservations.find(reservationId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ReservationBrief reservation = found.get();

        if (!"CHECKED_OUT".equals(reservation.status())) {
            // 취소된 예약이다. 이벤트가 만들어진 뒤에 바뀌었을 수 있으므로 지금 값을 본다.
            log.debug("체크아웃 상태가 아니라 청소 태스크를 만들지 않는다. "
                    + "reservationId={} status={}", reservationId, reservation.status());
            return Optional.empty();
        }
        // 먼저 조회해 분기한다. 유일 제약 위반을 잡아서 넘기는 방식은 트랜잭션 안에서
        // 동작하지 않는다 — 예외가 경계를 넘는 순간 rollback-only 로 찍힌다.
        // 3주차·12주차·14주차에 같은 함정에 세 번 빠졌다.
        if (tasks.findByReservationIdAndTaskType(reservationId, TaskType.CLEANING).isPresent()) {
            log.debug("이미 만든 청소 태스크라 넘긴다. reservationId={}", reservationId);
            return Optional.empty();
        }

        StayTimes times = unitCatalog.stayTimesOf(reservation.propertyId());
        ZoneId zone = ZoneId.systemDefault();
        OffsetDateTime dueFrom = reservation.checkOut().atTime(times.checkOut())
                .atZone(zone).toOffsetDateTime();

        // 기한의 끝은 다음 체크인 시각이다. 다음 예약이 없으면 열어 둔다.
        OffsetDateTime dueTo = reservations
                .nextCheckIn(reservation.unitId(), reservation.checkOut())
                .map(date -> date.atTime(times.checkIn()).atZone(zone).toOffsetDateTime())
                .orElse(null);

        OpsTask task = tasks.saveAndFlush(new OpsTask(
                reservation.propertyId(), reservation.unitId(), reservationId,
                TaskType.CLEANING, dueFrom, dueTo));

        // 체크아웃했으니 청소가 필요한 상태다.
        housekeeping.markDirty(reservation.unitId());
        notes.note(reservationId, noticeOf(reservation, task));

        log.info("청소 태스크를 만들었다. taskId={} reservationId={} unitId={} 기한={}~{}",
                task.getId(), reservationId, reservation.unitId(), dueFrom, dueTo);
        return Optional.of(task);
    }

    /**
     * 담당자에게 갈 알림 본문.
     *
     * <p>게스트 이름과 연락처를 담지 않는다. 청소하는 사람이 알아야 하는 것은 어느
     * 방을 언제까지인가이고, 이 메시지는 게스트와의 대화에 남는다(ADR 0007 의 선).
     */
    private String noticeOf(ReservationBrief reservation, OpsTask task) {
        String unitName = unitCatalog.nameOf(reservation.unitId());
        return task.getDueTo() == null
                ? "청소 태스크가 생겼습니다. %s / 체크아웃 %s / 다음 예약 없음"
                        .formatted(unitName, reservation.checkOut())
                : "청소 태스크가 생겼습니다. %s / 체크아웃 %s / 다음 체크인까지"
                        .formatted(unitName, reservation.checkOut());
    }

    // --- 조회와 이동 -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<OpsTask> board(Long orgId, LocalDate from, LocalDate to, String assignee) {
        List<Long> propertyIds = owned.propertyIdsOf(orgId);
        if (propertyIds.isEmpty()) {
            return List.of();
        }
        ZoneId zone = ZoneId.systemDefault();
        // 필터가 없으면 넓은 경계를 준다. 질의에 null 타임스탬프를 바인딩하면
        // PostgreSQL 이 자료형을 추론하지 못해 거절한다.
        return tasks.search(propertyIds,
                from == null ? 아주_옛날 : from.atStartOfDay(zone).toOffsetDateTime(),
                to == null ? 아주_먼_훗날 : to.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                assignee == null || assignee.isBlank() ? null : assignee.trim());
    }

    /**
     * 칸을 옮긴다.
     *
     * <p><b>{@code DONE} 이면 판매 단위가 {@code CLEAN} 으로 돌아온다. 한 트랜잭션이다.</b>
     * 하나만 반영되면 "청소 끝났는데 더러움" 또는 그 반대가 남고, 둘 다 화면에서는
     * 정상으로 보인다(작업지시 12 의 2절 C).
     */
    @Transactional
    public OpsTask move(Long taskId, Long orgId, TaskStatus next) {
        OpsTask task = load(taskId, orgId);
        task.moveTo(next);
        if (next == TaskStatus.DONE && task.getUnitId() != null) {
            housekeeping.markClean(task.getUnitId());
        }
        return task;
    }

    @Transactional
    public OpsTask assign(Long taskId, Long orgId, String assigneeName) {
        OpsTask task = load(taskId, orgId);
        task.assignTo(assigneeName);
        return task;
    }

    private OpsTask load(Long taskId, Long orgId) {
        OpsTask task = tasks.findById(taskId)
                .orElseThrow(() -> new OpsTaskNotFoundException(taskId));
        if (!owned.ownsProperty(task.getPropertyId(), orgId)) {
            // 소유가 아닌 자원은 "없음"으로 답한다. 403 은 존재를 알려 준다.
            throw new OpsTaskNotFoundException(taskId);
        }
        return task;
    }
}
