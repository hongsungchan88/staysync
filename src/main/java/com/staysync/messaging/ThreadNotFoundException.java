package com.staysync.messaging;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 그런 스레드가 없거나 남의 조직의 것이다.
 *
 * <p>둘을 구분하지 않는다. 403 으로 답하면 식별자를 훑어 남의 조직에 대화가 몇 개
 * 있는지 셀 수 있다. 이 프로젝트가 다른 조회에서 쓰는 것과 같은 규칙이다.
 */
public class ThreadNotFoundException extends DomainException {

    public ThreadNotFoundException(Long threadId) {
        super("THREAD_NOT_FOUND", "대화를 찾을 수 없습니다. id=" + threadId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
