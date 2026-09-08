package com.staysync.payment;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 웹훅 서명 검증에 실패했다. 계획서 14.2 의 검증 시나리오 10.
 *
 * <p><b>본문을 읽기 전에 막는다.</b> 서명이 맞지 않으면 그 본문은 포트원이 보낸 것이
 * 아니고, 그 안의 결제 식별자나 금액을 근거로 아무것도 하지 않는다.
 *
 * <p>400 이다. 401 로 답하면 포트원이 자격 증명 문제로 보고 재시도할 수 있는데,
 * 서명이 틀린 요청은 몇 번을 다시 보내도 틀리다.
 *
 * <p><b>이유를 자세히 적지 않는다.</b> "타임스탬프가 오래됐다"와 "서명이 다르다"를
 * 구분해 알려 주면 위조를 시도하는 쪽에 단서가 된다.
 */
public class InvalidWebhookException extends DomainException {

    public InvalidWebhookException() {
        super("INVALID_WEBHOOK", "웹훅 서명을 확인할 수 없습니다.");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
