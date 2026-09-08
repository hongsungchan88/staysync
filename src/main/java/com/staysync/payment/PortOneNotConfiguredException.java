package com.staysync.payment;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 포트원 설정이 비어 있는데 결제를 쓰려 했다.
 *
 * <p>기동은 막지 않는다(그 근거는 {@link PortOneProperties}). 대신 <b>실제로 쓰는
 * 순간</b> 여기서 막는다. 특히 웹훅 시크릿이 없을 때 조용히 통과시키면 아무나 결제
 * 완료 웹훅을 보내 예약을 확정시킬 수 있다.
 *
 * <p>500 이다. 손님이 고칠 수 있는 것이 아니라 운영자가 설정을 빠뜨린 것이다.
 */
public class PortOneNotConfiguredException extends DomainException {

    public PortOneNotConfiguredException(String envName) {
        super("PORTONE_NOT_CONFIGURED",
                "포트원 설정이 없습니다. 환경 변수 %s 를 지정하세요. (.env.example 참고)"
                        .formatted(envName));
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
