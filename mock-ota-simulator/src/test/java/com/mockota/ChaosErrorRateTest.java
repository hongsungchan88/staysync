package com.mockota;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * 완료 조건 6의 나머지 절반. 에러율 100% 면 채널 표면이 전부 503 이다.
 *
 * <p>0% 쪽은 {@link ChannelApiTest} 가 본다. 그 파일은 기본값(에러율 0)으로 돌고
 * 전부 통과한다. 악조건은 프로퍼티로만 바꾸므로(결정 3번) 두 값을 한 컨텍스트에서
 * 볼 수 없고, 그래서 파일이 갈렸다.
 *
 * <p>12주차 완료 조건의 "5% 에러율로 ARI 100건"이 이 장치 위에 선다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "mockota.chaos.error-rate=1.0")
class ChaosErrorRateTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("에러율 100% 면 ARI 수신이 전부 503 이다")
    void ari_가_전부_503() {
        for (int i = 0; i < 10; i++) {
            assertThat(rest.postForEntity("/api/ari", ApiKeys.signed("{\"n\":1}"), String.class)
                    .getStatusCode().value())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        }
    }

    @Test
    @DisplayName("에러율 100% 면 예약 목록 조회도 전부 503 이다")
    void 예약_조회도_전부_503() {
        for (int i = 0; i < 10; i++) {
            assertThat(rest.exchange("/api/bookings", HttpMethod.GET, ApiKeys.signed(null),
                    String.class).getStatusCode().value())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        }
    }

    @Test
    @DisplayName("악조건이 시나리오 경로는 건드리지 않는다")
    void 시나리오는_지나간다() {
        // 악조건이 겨냥하는 것은 우리 클라이언트의 타임아웃·재시도·백오프이고,
        // 시나리오 엔드포인트는 채널의 표면이 아니라 테스트가 쥐는 손잡이다.
        // 손잡이까지 실패시키면 에러율 100% 에서 시험 준비 자체가 불가능해진다.
        assertThat(rest.postForEntity("/api/scenarios/duplicate",
                ApiKeys.signed(new ScenarioRequest(null, null, null, null, null, null, 1)),
                String.class).getStatusCode().value())
                .isEqualTo(HttpStatus.OK.value());
    }
}
