package com.staysync.ops.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 운영 태스크. 지금은 청소뿐이다(계획서 8.6).
 *
 * <p><b>{@code unit_id} 가 계획서의 {@code roomId} 자리다.</b> 2계층 모델에 호실이
 * 없다(결정문서 01). 개인 호스트에게는 판매 상품이 곧 물리 공간이라 청소 대상도
 * 판매 단위다.
 *
 * <p>기한은 <b>체크아웃 시각부터 다음 체크인 시각까지</b>다. 다음 예약이 없으면
 * {@code dueTo} 가 비어 있고, 그것이 "열려 있다"는 뜻이다 — 없는 날짜를 지어내면
 * 아직 오지 않은 마감이 지난 것으로 표시된다.
 *
 * <p>담당자는 이름 문자열이다. 계정을 주려면 역할별 인가가 필요하고 그건 ADR 0004 가
 * 3~5단계로 미뤄 둔 것이다(작업지시 12 의 5절 3번).
 */
@Entity
@Table(name = "ops_task")
public class OpsTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "unit_id")
    private Long unitId;

    /**
     * 이 태스크를 만든 예약.
     *
     * <p>{@code uq_task_reservation} 이 이 값과 종류로 유일하다. 같은 체크아웃
     * 이벤트가 두 번 와도 태스크는 하나다 — Outbox 는 최소 1회 전달이다.
     */
    @Column(name = "reservation_id")
    private Long reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 20)
    private TaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus status = TaskStatus.TODO;

    @Column(name = "assignee_name", length = 100)
    private String assigneeName;

    @Column(name = "due_from")
    private OffsetDateTime dueFrom;

    @Column(name = "due_to")
    private OffsetDateTime dueTo;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column
    private String memo;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected OpsTask() {
    }

    public OpsTask(Long propertyId, Long unitId, Long reservationId, TaskType taskType,
                   OffsetDateTime dueFrom, OffsetDateTime dueTo) {
        this.propertyId = propertyId;
        this.unitId = unitId;
        this.reservationId = reservationId;
        this.taskType = taskType;
        this.dueFrom = dueFrom;
        this.dueTo = dueTo;
    }

    public Long getId() {
        return id;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getUnitId() {
        return unitId;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public String getAssigneeName() {
        return assigneeName;
    }

    public OffsetDateTime getDueFrom() {
        return dueFrom;
    }

    public OffsetDateTime getDueTo() {
        return dueTo;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public String getMemo() {
        return memo;
    }

    /** 기한이 지났는지. 기한이 열려 있으면 지날 수 없다. */
    public boolean isOverdue(OffsetDateTime now) {
        return dueTo != null && status != TaskStatus.DONE && now.isAfter(dueTo);
    }

    /**
     * 칸을 옮긴다.
     *
     * <p>{@code DONE} 으로 가면 완료 시각을 찍고, 거기서 나오면 지운다. 되돌린
     * 태스크에 완료 시각이 남아 있으면 "언제 끝났나"가 거짓이 된다.
     */
    public void moveTo(TaskStatus next) {
        if (next == null) {
            throw new IllegalArgumentException("옮길 칸이 필요합니다.");
        }
        this.completedAt = next == TaskStatus.DONE ? OffsetDateTime.now() : null;
        this.status = next;
    }

    public void assignTo(String name) {
        // 빈 문자열은 담당자가 없는 것으로 본다. 화면에서 이름 칸을 비우는 조작이다.
        this.assigneeName = name == null || name.isBlank() ? null : name.trim();
    }

    public void changeMemo(String memo) {
        this.memo = memo;
    }
}
