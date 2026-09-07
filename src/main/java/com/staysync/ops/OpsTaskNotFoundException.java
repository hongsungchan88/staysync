package com.staysync.ops;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 태스크가 없거나 이 조직의 것이 아니다.
 *
 * <p>둘을 구분하지 않는다. 403 으로 존재를 알려 주면 식별자를 훑어 다른 조직의 태스크가
 * 있는지 알아낼 수 있다. {@code OwnedResources} 가 세운 규칙과 같다.
 */
public class OpsTaskNotFoundException extends DomainException {

    public OpsTaskNotFoundException(Long taskId) {
        super("OPS_TASK_NOT_FOUND", "태스크를 찾을 수 없습니다. id=" + taskId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
