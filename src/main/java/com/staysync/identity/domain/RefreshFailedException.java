package com.staysync.identity.domain;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 리프레시 토큰으로 갱신할 수 없다.
 *
 * <p>쿠키 없음, 존재하지 않는 토큰, 만료, 무효화, 재사용 탐지가 모두 이 하나로 나간다.
 * 재사용이 탐지됐다는 사실을 공격자에게 알려 줄 이유가 없다. 사유 구분은 로그에만 남는다.
 */
public class RefreshFailedException extends DomainException {

    public RefreshFailedException() {
        super("REFRESH_FAILED", "다시 로그인해 주세요.");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNAUTHORIZED;
    }
}
