package com.mockota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;

/**
 * 완료 조건 5. 지연 주입.
 *
 * <p>12주차의 타임아웃과 서킷브레이커가 이걸로 검증된다. 실제 OTA 가 느려지는 상황을
 * 우리가 만들 수 있는 유일한 방법이다 — Channex 스테이징은 남의 서버라 느리게 만들 수 없다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mockota.chaos.latency-ms=400")
class ChaosLatencyTest {

    private static final long INJECTED_MS = 400;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("지연을 주입하면 응답이 그만큼 늦는다")
    void 응답이_주입한_만큼_늦는다() {
        long start = System.nanoTime();
        rest.exchange("/api/bookings", HttpMethod.GET, ApiKeys.signed(null), String.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // 하한만 본다. 상한을 걸면 기계가 바쁠 때 실패하는 테스트가 되고, 그건
        // 결함을 숨긴다.
        assertThat(elapsed.toMillis())
                .as("주입한 %dms 보다 오래 걸려야 한다", INJECTED_MS)
                .isGreaterThanOrEqualTo(INJECTED_MS);
    }

    @Test
    @DisplayName("ARI 수신에도 같은 지연이 걸린다")
    void ari_에도_지연이_걸린다() {
        long start = System.nanoTime();
        rest.postForEntity("/api/ari", ApiKeys.signed("{\"n\":1}"), String.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis())
                .isGreaterThanOrEqualTo(INJECTED_MS);
    }
}
