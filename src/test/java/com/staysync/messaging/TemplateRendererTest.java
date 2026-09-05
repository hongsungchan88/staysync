package com.staysync.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>완료 조건 7·8.</b> 템플릿 치환.
 *
 * <p>8번이 이 파일의 핵심이다. <b>채울 값이 없으면 발송을 막는다.</b> 빈칸이나
 * 치환되지 않은 변수가 그대로 나간 메시지는 되돌릴 수 없고, 우리 쪽 로그에는
 * "발송 성공"으로 남는다.
 *
 * <p>스프링을 띄우지 않는다. 치환은 순수 함수다.
 */
class TemplateRendererTest {

    private static final String 템플릿 =
            "{{guestName}} 님, {{propertyName}} 입니다. {{checkIn}} 체크인 안내드립니다.";

    @Test
    @DisplayName("변수가 값으로 치환된다")
    void 변수를_치환한다() {
        String rendered = TemplateRenderer.render(템플릿, Map.of(
                "guestName", "홍길동",
                "propertyName", "성수동 오피스텔",
                "checkIn", "2026-12-24"));

        assertThat(rendered)
                .isEqualTo("홍길동 님, 성수동 오피스텔 입니다. 2026-12-24 체크인 안내드립니다.");
        assertThat(rendered).doesNotContain("{{");
    }

    @Test
    @DisplayName("채울 값이 없으면 발송을 막는다")
    void 값이_없으면_예외다() {
        // 게스트 이름이 없다. 그대로 내보내면 "{{guestName}} 님" 이 나간다.
        Map<String, String> 값 = new HashMap<>();
        값.put("propertyName", "성수동 오피스텔");
        값.put("checkIn", "2026-12-24");

        assertThatThrownBy(() -> TemplateRenderer.render(템플릿, 값))
                .isInstanceOf(TemplateVariableMissingException.class)
                .hasMessageContaining("guestName");
    }

    @Test
    @DisplayName("빈 문자열은 값이 없는 것으로 본다")
    void 빈_값도_막는다() {
        // null 만 막으면 "안녕하세요 님" 이 나간다. 빈칸이 나가는 것도 사고다.
        Map<String, String> 값 = new HashMap<>();
        값.put("guestName", "  ");
        값.put("propertyName", "성수동 오피스텔");
        값.put("checkIn", "2026-12-24");

        assertThatThrownBy(() -> TemplateRenderer.render(템플릿, 값))
                .isInstanceOf(TemplateVariableMissingException.class);
    }

    @Test
    @DisplayName("채울 수 없는 변수를 전부 알려 준다")
    void 빠진_변수를_모아_알려_준다() {
        // 하나씩 고치게 하면 저장할 때마다 다른 이름이 나온다.
        assertThat(TemplateRenderer.missingVariables(템플릿, Map.of("checkIn", "2026-12-24")))
                .containsExactly("guestName", "propertyName");
    }

    @Test
    @DisplayName("값에 든 특수문자가 치환 문법으로 읽히지 않는다")
    void 값의_특수문자가_본문을_망가뜨리지_않는다() {
        // 정규식 치환은 $1 과 백슬래시를 참조로 읽는다. 게스트 이름 하나가 메시지
        // 전체를 망가뜨리거나 IllegalArgumentException 을 내면 안 된다.
        String rendered = TemplateRenderer.render("{{guestName}} 님 안녕하세요",
                Map.of("guestName", "$1 \\ 홍길동"));

        assertThat(rendered).isEqualTo("$1 \\ 홍길동 님 안녕하세요");
    }

    @Test
    @DisplayName("변수가 없는 템플릿은 그대로 나간다")
    void 변수가_없으면_그대로다() {
        assertThat(TemplateRenderer.render("체크아웃은 11시입니다.", Map.of()))
                .isEqualTo("체크아웃은 11시입니다.");
    }

    @Test
    @DisplayName("본문이 비어 있으면 보내지 않는다")
    void 빈_본문은_막는다() {
        assertThatThrownBy(() -> TemplateRenderer.render("   ", Map.of()))
                .isInstanceOf(TemplateVariableMissingException.class);
    }
}
