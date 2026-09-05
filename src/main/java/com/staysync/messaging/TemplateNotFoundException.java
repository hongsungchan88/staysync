package com.staysync.messaging;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** 그런 템플릿이 없거나 남의 조직의 것이다. */
public class TemplateNotFoundException extends DomainException {

    public TemplateNotFoundException(Long templateId) {
        super("TEMPLATE_NOT_FOUND", "템플릿을 찾을 수 없습니다. id=" + templateId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
