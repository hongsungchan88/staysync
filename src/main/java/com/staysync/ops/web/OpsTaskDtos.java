package com.staysync.ops.web;

import com.staysync.ops.domain.OpsTask;
import com.staysync.ops.domain.TaskStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** 태스크 API 의 요청·응답 형태. */
final class OpsTaskDtos {

    private OpsTaskDtos() {
    }

    /**
     * 칸 이동과 담당자 지정.
     *
     * <p>{@code assigneeName} 의 빈 문자열은 "담당자를 지운다"는 뜻이다. {@code null}
     * 과 다르다 — {@code null} 은 건드리지 않는 것이다.
     */
    record MoveTaskRequest(TaskStatus status, @Size(max = 100) String assigneeName) {

        @AssertTrue(message = "바꿀 항목이 하나도 없습니다.")
        boolean isSomethingToChange() {
            // 아무것도 바꾸지 않는 요청에 200 을 주면 화면은 반영된 것으로 읽는다.
            return status != null || assigneeName != null;
        }
    }

    /**
     * 보드에 그릴 한 건.
     *
     * <p><b>기한 초과를 서버가 판정한다.</b> 브라우저 시계로 계산하면 시각이 어긋난
     * 기기에서 멀쩡한 태스크가 빨갛게 뜬다. 판정에 쓴 기준 시각은 응답에 담지 않는다 —
     * 화면이 그 값으로 다시 계산할 이유가 없다.
     */
    record TaskResponse(Long id,
                        Long unitId,
                        String unitName,
                        Long reservationId,
                        String taskType,
                        TaskStatus status,
                        String assigneeName,
                        OffsetDateTime dueFrom,
                        OffsetDateTime dueTo,
                        OffsetDateTime completedAt,
                        boolean overdue) {

        static TaskResponse of(OpsTask task, String unitName, OffsetDateTime now) {
            return new TaskResponse(
                    task.getId(), task.getUnitId(), unitName, task.getReservationId(),
                    task.getTaskType().name(), task.getStatus(), task.getAssigneeName(),
                    task.getDueFrom(), task.getDueTo(), task.getCompletedAt(),
                    task.isOverdue(now));
        }
    }
}
