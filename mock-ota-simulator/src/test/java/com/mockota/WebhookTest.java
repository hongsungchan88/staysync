package com.mockota;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 완료 조건 3. 웹훅이 지정한 URL 로 실제로 POST 된다.
 *
 * <p><b>받을 곳이 아직 없다.</b> 우리 앱의 웹훅 수신 엔드포인트는 12~13주차다. 그래서
 * 받는 쪽을 테스트 안에 세운다 — JDK 의 {@link HttpServer} 하나면 된다. 여기서 확인할 수
 * 있는 것은 "지정한 곳으로 실제로 보내는가"까지이고, 그게 이번 주의 범위다.
 *
 * <p>포트를 0으로 열어 OS 가 준 번호를 {@link DynamicPropertySource} 로 넘긴다.
 * 고정 포트를 쓰면 다른 테스트나 개발자의 프로세스와 부딪히고, 그건 "가끔 실패하는
 * 테스트"가 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookTest {

    private record Received(String body, String apiKey) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static HttpServer receiver;

    @DynamicPropertySource
    static void 받는_쪽을_세운다(DynamicPropertyRegistry registry) throws IOException {
        receiver = HttpServer.create(new InetSocketAddress(0), 0);
        receiver.createContext("/hooks/bookings", exchange -> {
            RECEIVED.add(new Received(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst(ApiKeyFilter.HEADER)));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        registry.add("mockota.webhook-url",
                () -> "http://localhost:" + receiver.getAddress().getPort() + "/hooks/bookings");
    }

    @AfterAll
    static void 받는_쪽을_내린다() {
        receiver.stop(0);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BookingStore bookingStore;

    @BeforeEach
    void 비운다() {
        RECEIVED.clear();
        bookingStore.clear();
    }

    @Test
    @DisplayName("예약이 생기면 지정한 URL 로 POST 한다")
    void 웹훅이_실제로_나간다() {
        rest.postForEntity("/api/scenarios/duplicate",
                ApiKeys.signed(new ScenarioRequest("BK-HOOK", "room-7", null, null, null, null, null, 1)),
                Object.class);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !RECEIVED.isEmpty());

        assertThat(RECEIVED).hasSize(1);
        assertThat(RECEIVED.get(0).body()).contains("BK-HOOK").contains("room-7");
    }

    @Test
    @DisplayName("웹훅 본문이 폴링 응답과 같은 형식이다")
    void 푸시와_폴링이_같은_형식이다() {
        rest.postForEntity("/api/scenarios/duplicate",
                ApiKeys.signed(new ScenarioRequest("BK-FMT", null, null, null, null, null, null, 1)),
                Object.class);
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !RECEIVED.isEmpty());

        String polled = rest.exchange("/api/bookings", HttpMethod.GET, ApiKeys.signed(null),
                String.class).getBody();

        // RestClient 는 그냥 두면 애플리케이션의 ObjectMapper 가 아니라 자기 기본
        // 변환기를 쓴다. 그러면 폴링은 snake_case 에 ISO 날짜인데 웹훅은 camelCase 에
        // 날짜가 배열로 나가고, 12주차 어댑터가 매핑을 두 벌 갖게 된다.
        String pushed = RECEIVED.get(0).body();
        assertThat(pushed).contains("booking_id").doesNotContain("bookingId");
        assertThat(pushed).contains("\"check_in\":\"").as("날짜가 ISO 문자열이어야 한다");
        assertThat(polled).contains("booking_id");
    }

    @Test
    @DisplayName("웹훅에도 자기 키를 실어 보낸다")
    void 웹훅에_키가_실린다() {
        rest.postForEntity("/api/scenarios/duplicate",
                ApiKeys.signed(new ScenarioRequest("BK-KEY", null, null, null, null, null, null, 1)),
                Object.class);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !RECEIVED.isEmpty());

        // 12~13주차의 수신 엔드포인트가 발신자를 확인할 수 있어야 한다.
        assertThat(RECEIVED.get(0).apiKey()).isEqualTo(ApiKeys.VALID);
    }

    @Test
    @DisplayName("중복 전송은 웹훅도 두 번 나간다")
    void 중복은_웹훅도_두_번이다() {
        rest.postForEntity("/api/scenarios/duplicate",
                ApiKeys.signed(new ScenarioRequest("BK-DUP", null, null, null, null, null, null, 2)),
                Object.class);

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> RECEIVED.size() >= 2);

        // 실제 웹훅은 최소 1회 전달이라 같은 것이 여러 번 온다. 시뮬레이터가 여기서
        // 합쳐 버리면 우리 쪽 멱등성을 검증할 수 없다.
        assertThat(RECEIVED).hasSize(2);
        assertThat(RECEIVED).allSatisfy(received ->
                assertThat(received.body()).contains("BK-DUP"));
    }
}
