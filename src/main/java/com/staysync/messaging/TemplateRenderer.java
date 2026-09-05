package com.staysync.messaging;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 템플릿의 변수를 치환한다. 순수 함수다.
 *
 * <p><b>채울 값이 없으면 예외다.</b> 이 클래스의 존재 이유가 그 한 줄이다. 빈 문자열로
 * 대신하거나 변수를 그대로 두면 그 메시지가 게스트에게 나가고, <b>나간 뒤에는 되돌릴
 * 수 없다.</b> 우리 쪽 로그에는 "발송 성공"으로 남는다.
 *
 * <p>변수 이름을 모아 두지 않는다. 템플릿에 적힌 것을 그대로 찾아 값 목록과 맞춰
 * 보는 편이 낫다 — 목록을 따로 두면 템플릿에는 있는데 목록에 없는 이름이 생기고,
 * 그때 아무도 알아채지 못한다.
 */
public final class TemplateRenderer {

    /** {@code {{guestName}}} 같은 이중 중괄호. 이름은 영문·숫자·밑줄이다. */
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private TemplateRenderer() {
    }

    /**
     * 치환한다.
     *
     * @param values 변수 이름 → 값. {@code null} 이거나 빈 문자열인 값은 <b>없는 것으로
     *               본다</b> — 게스트 이름이 비어 있으면 "안녕하세요 님" 이 나간다
     * @throws TemplateVariableMissingException 채울 수 없는 변수가 하나라도 있으면
     */
    public static String render(String template, Map<String, String> values) {
        if (template == null || template.isBlank()) {
            throw new TemplateVariableMissingException(List.of("(본문이 비어 있습니다)"));
        }
        List<String> missing = missingVariables(template, values);
        if (!missing.isEmpty()) {
            throw new TemplateVariableMissingException(missing);
        }

        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            // 값에 $ 나 백슬래시가 들어 있어도 그대로 넣는다. 치환 문법으로 읽히면
            // 게스트 이름 하나가 메시지 전체를 망가뜨린다.
            matcher.appendReplacement(out, Matcher.quoteReplacement(values.get(matcher.group(1))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 채울 수 없는 변수 이름. 비어 있으면 보낼 수 있다.
     *
     * <p>화면이 발송 전에 이걸 물어 버튼을 막는다. 서버가 예외로 막는 것이 최종
     * 방어선이고, 화면은 사용자가 이유를 미리 알게 하는 자리다.
     */
    public static List<String> missingVariables(String template, Map<String, String> values) {
        if (template == null) {
            return List.of();
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = VARIABLE.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values == null ? null : values.get(name);
            if (value == null || value.isBlank()) {
                missing.add(name);
            }
        }
        return new ArrayList<>(missing);
    }
}
