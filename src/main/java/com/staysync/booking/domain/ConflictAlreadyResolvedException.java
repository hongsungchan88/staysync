package com.staysync.booking.domain;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 이미 해소된 충돌을 다시 해소하려 했다.
 *
 * <p>조용히 넘기지 않는다. 업그레이드 배정은 재고를 옮기는 일이라, 두 번 부르면
 * <b>방이 두 번 옮겨진다.</b> 화면에서 두 번 눌리거나 두 사람이 동시에 처리하는
 * 상황이 실제로 있고, 그때 두 번째가 성공으로 보이면 안 된다.
 */
public class ConflictAlreadyResolvedException extends DomainException {

    public ConflictAlreadyResolvedException(Long conflictId, String status) {
        super("CONFLICT_ALREADY_RESOLVED",
                "이미 처리된 충돌입니다. id=%s 상태=%s".formatted(conflictId, status));
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
