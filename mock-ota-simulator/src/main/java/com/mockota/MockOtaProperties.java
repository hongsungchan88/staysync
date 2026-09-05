package com.mockota;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시뮬레이터 설정. {@code mockota.*} 에 대응한다.
 *
 * <p><b>{@code .env} 를 읽지 않는다.</b> 실제 Channex 키와 무관하다. 여기의 키는
 * 시뮬레이터 자신의 설정이고, 값이 무엇이든 상관없는 단순한 문자열이다.
 *
 * @param apiKey     채널이 요구하는 API 키. 헤더 {@code X-Api-Key} 로 받는다
 * @param webhookUrl 예약이 생길 때 POST 할 곳. 비어 있으면 보내지 않는다
 */
@ConfigurationProperties(prefix = "mockota")
public record MockOtaProperties(String apiKey, String webhookUrl, Chaos chaos) {

    public MockOtaProperties {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "mockota.api-key 가 없습니다. 검사할 키가 없으면 자격 증명 검사가 무의미해집니다.");
        }
        if (chaos == null) {
            chaos = new Chaos(0L, 0.0, null);
        }
    }

    /**
     * 악조건 주입 설정. 계획서 6.4 의 {@code ChaosFilter} 가 명세다.
     *
     * <p><b>런타임에 바꾸는 엔드포인트를 만들지 않았다.</b> 프로퍼티로 충분하고, 한
     * 테스트 안에서 값을 바꿔야 한다면 컨텍스트를 나누면 된다. 지금 쓸 곳이 없는 것을
     * 미리 만들지 않는다.
     *
     * @param latencyMs 응답을 늦출 시간. 타임아웃과 서킷브레이커 검증용
     * @param errorRate 503 으로 떨어뜨릴 비율(0.0~1.0). 재시도와 백오프 검증용
     * @param seed      난수 시드. <b>지정하면 실패 순서가 재현된다.</b> 비우면 매번
     *                  새로 뽑고 그 값을 기동 로그에 남긴다 — 나중에 같은 실패를
     *                  다시 만들 수 있어야 하기 때문이다
     */
    public record Chaos(long latencyMs, double errorRate, Long seed) {

        public Chaos {
            if (errorRate < 0.0 || errorRate > 1.0) {
                throw new IllegalStateException("mockota.chaos.error-rate 는 0.0~1.0 이어야 합니다: " + errorRate);
            }
            if (latencyMs < 0) {
                throw new IllegalStateException("mockota.chaos.latency-ms 는 0 이상이어야 합니다: " + latencyMs);
            }
        }
    }
}
