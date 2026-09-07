package com.staysync.ops.web;

import com.staysync.ops.OpsTaskService;
import com.staysync.ops.domain.OpsTask;
import com.staysync.ops.web.OpsTaskDtos.MoveTaskRequest;
import com.staysync.ops.web.OpsTaskDtos.TaskResponse;
import com.staysync.property.UnitCatalog;
import com.staysync.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 청소 태스크 칸반 API. 계획서 8.6 의 {@code /ops/tasks} 화면이 쓴다.
 *
 * <p>조회와 변경이 전부 {@link OpsTaskService} 를 거쳐 조직으로 좁혀진다.
 * 소유가 아니면 404 다.
 */
@RestController
@RequestMapping("/api/ops/tasks")
class OpsTaskController {

    private final OpsTaskService service;
    private final UnitCatalog unitCatalog;

    OpsTaskController(OpsTaskService service, UnitCatalog unitCatalog) {
        this.service = service;
        this.unitCatalog = unitCatalog;
    }

    /**
     * 보드 한 장.
     *
     * <p>칸별로 나누어 주지 않는다. 화면이 상태로 묶는 편이 낫다 — 칸 사이 이동에서
     * 낙관적 업데이트를 하려면 어차피 한 배열을 쥐고 옮겨야 한다.
     */
    @GetMapping
    List<TaskResponse> board(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String assignee) {
        List<OpsTask> found = service.board(orgId(), from, to, assignee);
        Map<Long, String> unitNames = unitNamesOf(found);
        OffsetDateTime now = OffsetDateTime.now();
        return found.stream()
                .map(task -> TaskResponse.of(task, unitNames.get(task.getUnitId()), now))
                .toList();
    }

    /**
     * 칸을 옮기거나 담당자를 바꾼다.
     *
     * <p>둘을 한 엔드포인트로 둔다. 화면의 조작은 다르지만 보내는 것은 같은 태스크의
     * 부분 수정이고, {@code null} 인 항목은 건드리지 않는다.
     */
    @PatchMapping("/{taskId}")
    TaskResponse patch(@PathVariable Long taskId, @Valid @RequestBody MoveTaskRequest request) {
        Long orgId = orgId();
        // 둘 다 올 수 있다. 담당자를 먼저 바꾸고 칸을 옮긴다 — 순서가 반대면 완료
        // 처리와 담당자 지정이 한 요청에 왔을 때 완료된 태스크에 담당자가 붙는다.
        OpsTask task = request.assigneeName() == null
                ? null : service.assign(taskId, orgId, request.assigneeName());
        if (request.status() != null) {
            task = service.move(taskId, orgId, request.status());
        }
        return TaskResponse.of(task, unitNameOf(task), OffsetDateTime.now());
    }

    /** 판매 단위 이름을 한 번에 읽는다. 태스크마다 물으면 왕복이 태스크 수만큼 는다. */
    private Map<Long, String> unitNamesOf(List<OpsTask> found) {
        Map<Long, String> names = new HashMap<>();
        for (OpsTask task : found) {
            if (task.getUnitId() != null) {
                names.computeIfAbsent(task.getUnitId(), unitCatalog::nameOf);
            }
        }
        return names;
    }

    private String unitNameOf(OpsTask task) {
        return task.getUnitId() == null ? null : unitCatalog.nameOf(task.getUnitId());
    }

    private static Long orgId() {
        return AuthenticatedUser.current()
                .orElseThrow(() -> new IllegalStateException(
                        "인증이 필요한 경로인데 주체가 없다. SecurityConfig 설정을 확인할 것."))
                .orgId();
    }
}
