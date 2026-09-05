package com.staysync.booking;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 그런 충돌이 없거나 남의 조직의 것이다.
 *
 * <p>둘을 구분하지 않는다. 소유가 아닌 것을 403 으로 답하면 식별자를 훑어 남의 숙소에
 * 충돌이 몇 건 있는지 셀 수 있다. 이 프로젝트가 다른 조회에서 쓰는 것과 같은 규칙이다.
 */
public class ConflictNotFoundException extends DomainException {

    public ConflictNotFoundException(Long conflictId) {
        super("CONFLICT_NOT_FOUND", "충돌을 찾을 수 없습니다. id=" + conflictId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
