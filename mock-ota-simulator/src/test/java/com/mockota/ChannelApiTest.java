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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 완료 조건 1·2. 채널 표면의 정상 경로.
 *
 * <p>악조건 설정은 {@code application.yml} 의 기본값(지연 0, 에러율 0)이다. 그래서 이
 * 파일 전체가 <b>완료 조건 6의 절반</b>이기도 하다 — 에러율이 0이면 전부 통과한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelApiTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AriStore ariStore;

    @Autowired
    private BookingStore bookingStore;

    @BeforeEach
    void 저장소를_비운다() {
        ariStore.clear();
        bookingStore.clear();
    }

    @Test
    @DisplayName("보낸 ARI 가 그대로 돌아온다")
    void 보낸_ari_를_그대로_돌려준다() throws Exception {
        // 시뮬레이터가 해석하지 않는다는 것을 보이려고 일부러 낯선 필드를 섞는다.
        // 타입을 정해 받았다면 unknown_extension 은 조용히 사라졌을 것이다.
        JsonNode sent = json.readTree("""
                {"room_id":"room-1","rate_plan_id":"rp-1",
                 "segments":[{"from":"2026-12-24","to":"2026-12-26","rate":250000,"availability":1}],
                 "unknown_extension":{"nested":[1,2,3]}}
                """);

        ResponseEntity<AriStore.Entry> posted =
                rest.postForEntity("/api/ari", ApiKeys.signed(sent), AriStore.Entry.class);
        assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<AriStore.Entry[]> stored = rest.exchange(
                "/api/ari", HttpMethod.GET, ApiKeys.signed(null), AriStore.Entry[].class);

        assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stored.getBody()).hasSize(1);
        assertThat(stored.getBody()[0].body())
                .as("해석하지 않고 그대로 담아야 한다. 낯선 필드도 남는다")
                .isEqualTo(sent);
    }

    @Test
    void 보낸_순서대로_쌓인다() throws Exception {
        for (int i = 1; i <= 3; i++) {
            rest.postForEntity("/api/ari",
                    ApiKeys.signed(json.readTree("{\"n\":%d}".formatted(i))), Void.class);
        }

        AriStore.Entry[] stored = rest.exchange(
                "/api/ari", HttpMethod.GET, ApiKeys.signed(null), AriStore.Entry[].class).getBody();

        // 12주차의 병합 버퍼가 "몇 번에 나눠 보냈나"를 이 순서로 확인한다.
        assertThat(stored).extracting(entry -> entry.body().get("n").asInt())
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("받은 ARI 를 전부 잊어버릴 수 있다")
    void ari_를_비우면_조회가_빈다() throws Exception {
        rest.postForEntity("/api/ari", ApiKeys.signed(json.readTree("{\"n\":1}")), Void.class);

        // 12주차의 "전송한 ARI 무시"가 이 경로다. 채널이 받았다고 답해 놓고 반영하지
        // 않은 상황을 만들고, 재동기화 배치가 다시 채우는지 본다.
        ResponseEntity<Void> deleted =
                rest.exchange("/api/ari", HttpMethod.DELETE, ApiKeys.signed(null), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(rest.exchange("/api/ari", HttpMethod.GET, ApiKeys.signed(null),
                AriStore.Entry[].class).getBody()).isEmpty();
    }

    @Test
    @DisplayName("예약 목록 조회가 만들어 둔 예약을 돌려준다")
    void 예약_목록을_돌려준다() {
        rest.postForEntity("/api/scenarios/duplicate",
                ApiKeys.signed(new ScenarioRequest("BK-1", "room-9", null, null, null, null, null, 1)),
                Object.class);

        ResponseEntity<MockBooking[]> polled = rest.exchange(
                "/api/bookings", HttpMethod.GET, ApiKeys.signed(null), MockBooking[].class);

        assertThat(polled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(polled.getBody()).hasSize(1);
        assertThat(polled.getBody()[0].bookingId()).isEqualTo("BK-1");
        assertThat(polled.getBody()[0].roomId()).isEqualTo("room-9");
    }

    @Test
    @DisplayName("예약 JSON 은 snake_case 로 나간다")
    void 예약_필드는_우리_형식과_다르다() {
        rest.postForEntity("/api/scenarios/duplicate",
                ApiKeys.signed(new ScenarioRequest("BK-2", null, null, null, null, null, null, 1)),
                Object.class);

        String raw = rest.exchange("/api/bookings", HttpMethod.GET, ApiKeys.signed(null),
                String.class).getBody();

        // 이름이 우연히 맞아떨어져 통과하면 12주차 어댑터가 정말로 매핑을 하는지
        // 알 수 없다. 채널의 형식은 우리 형식과 달라야 한다.
        assertThat(raw).contains("booking_id").contains("check_in").doesNotContain("bookingId");
    }
}
