package com.staysync.messaging;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** 그런 규칙이 없거나 남의 조직의 것이다. */
public class MessageRuleNotFoundException extends DomainException {

    public MessageRuleNotFoundException(Long ruleId) {
        super("MESSAGE_RULE_NOT_FOUND", "자동 발송 규칙을 찾을 수 없습니다. id=" + ruleId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
