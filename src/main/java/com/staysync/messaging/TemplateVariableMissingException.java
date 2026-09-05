package com.staysync.messaging;

import com.staysync.shared.error.DomainException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 템플릿에 채울 수 없는 변수가 있어 발송을 막았다.
 *
 * <p><b>조용히 지나가지 않는다.</b> 빈 문자열로 대신하면 "안녕하세요 님" 이 나가고,
 * 변수를 그대로 두면 이중 중괄호가 게스트에게 보인다. 둘 다 되돌릴 수 없고 우리 쪽
 * 로그에는 발송 성공으로 남는다.
 */
public class TemplateVariableMissingException extends DomainException {

    private final List<String> variables;

    public TemplateVariableMissingException(List<String> variables) {
        super("TEMPLATE_VARIABLE_MISSING",
                "채울 수 없는 변수가 있어 보내지 않았습니다: " + String.join(", ", variables));
        this.variables = List.copyOf(variables);
    }

    /** 화면이 어느 변수가 비었는지 보여 줄 수 있게 담아 둔다. */
    public List<String> variables() {
        return variables;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
