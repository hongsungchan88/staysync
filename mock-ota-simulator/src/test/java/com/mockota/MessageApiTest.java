package com.mockota;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 메시징 표면. P4 14주차에 더했다.
 *
 * <p>여기서 지킬 것은 하나다 — <b>시뮬레이터는 나쁘게 군다.</b> 같은 메시지를 여러 번
 * 내놓고 시각을 거꾸로 매긴다. 여기서 바로잡으면 우리 쪽의 수신 멱등성을 검증할 것이
 * 사라진다(ADR 0011).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessageApiTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MessageStore messages;

    @BeforeEach
    void 저장소를_비운다() {
        messages.clear();
    }

    @Test
    @DisplayName("게스트 메시지 시나리오가 같은 식별자로 여러 번 내놓는다")
    void 중복해서_내놓는다() throws Exception {
        rest.postForEntity("/api/scenarios/guest-message",
                ApiKeys.signed(new ScenarioRequest("BK-1", null, null, null, null, null,
                        "체크인 시간을 늦출 수 있을까요?", 3)),
                String.class);

        JsonNode inbound = json.readTree(
                rest.exchange("/api/messages", org.springframework.http.HttpMethod.GET,
                        ApiKeys.signed(null), String.class).getBody());

        assertThat(inbound).hasSize(3);
        // 식별자가 같다. 우리 쪽이 한 건으로 흡수해야 한다.
        assertThat(inbound.get(0).get("message_id").asText())
                .isEqualTo(inbound.get(2).get("message_id").asText());
        assertThat(inbound.get(0).get("booking_id").asText()).isEqualTo("BK-1");
        assertThat(inbound.get(0).get("sender").asText()).isEqualTo("GUEST");
    }

    @Test
    @DisplayName("시각이 거꾸로 매겨져 나간다")
    void 순서를_뒤집어_내놓는다() throws Exception {
        rest.postForEntity("/api/scenarios/guest-message",
                ApiKeys.signed(new ScenarioRequest("BK-2", null, null, null, null, null,
                        "안녕하세요", 2)),
                String.class);

        JsonNode inbound = json.readTree(
                rest.exchange("/api/messages", org.springframework.http.HttpMethod.GET,
                        ApiKeys.signed(null), String.class).getBody());

        // 나중에 나간 것이 더 이른 시각을 갖는다. 우리 쪽 스레드의 last_message_at 이
        // 뒤로 가면 새 대화가 목록 아래로 밀린다.
        assertThat(inbound.get(1).get("sent_at").asText())
                .isLessThan(inbound.get(0).get("sent_at").asText());
    }

    @Test
    @DisplayName("보낸 메시지를 받아 두고 그대로 돌려준다")
    void 보낸_것을_되돌려_준다() throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/messages",
                ApiKeys.signed(new MockMessage(null, "thread-1", "BK-3", null,
                        "15시 이후 체크인 가능합니다.", null)),
                String.class);

        // 202 다. 받았다는 것과 게스트에게 전달됐다는 것은 다르다.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        JsonNode sent = json.readTree(
                rest.exchange("/api/messages/sent", org.springframework.http.HttpMethod.GET,
                        ApiKeys.signed(null), String.class).getBody());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("body").asText()).isEqualTo("15시 이후 체크인 가능합니다.");
        assertThat(sent.get(0).get("sender").asText()).isEqualTo("HOST");
    }

    @Test
    @DisplayName("본문을 해석하지 않는다. 치환되지 않은 변수도 그대로 받는다")
    void 본문을_검사하지_않는다() throws Exception {
        // 막는 것은 우리 쪽 일이다. 여기서 걸러 주면 그 검증이 사라진다.
        rest.postForEntity("/api/messages",
                ApiKeys.signed(new MockMessage(null, "thread-2", "BK-4", null,
                        "{{guestName}} 님 안녕하세요", null)),
                String.class);

        JsonNode sent = json.readTree(
                rest.exchange("/api/messages/sent", org.springframework.http.HttpMethod.GET,
                        ApiKeys.signed(null), String.class).getBody());
        assertThat(sent.get(0).get("body").asText()).isEqualTo("{{guestName}} 님 안녕하세요");
    }
}
