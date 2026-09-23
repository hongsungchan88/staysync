package com.staysync.ops;

import com.staysync.ops.domain.TaskStatus;
import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** 청소 태스크에 이미 손을 대서 체크아웃을 되돌릴 수 없다. */
public class CleaningAlreadyStartedException extends DomainException {

    public CleaningAlreadyStartedException(TaskStatus status) {
        super("CLEANING_ALREADY_STARTED",
                "청소 태스크가 이미 '%s' 상태라 체크아웃을 되돌릴 수 없습니다. 청소 칸반에서 먼저 확인하세요."
                        .formatted(label(status)));
    }

    private static String label(TaskStatus status) {
        return switch (status) {
            case TODO -> "할 일";
            case IN_PROGRESS -> "진행 중";
            case DONE -> "완료";
            case BLOCKED -> "막힘";
        };
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
