package com.mockota;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * 완료 조건 4. 자격 증명 검사.
 *
 * <p><b>이 검사가 없으면 12주차에 어댑터가 키를 빠뜨려도 테스트가 전부 통과한다.</b>
 * 드러나는 것은 실제 OTA 로 바꾸는 순간이고, 그때는 모든 요청이 401 이라 어디서부터
 * 잘못됐는지도 보이지 않는다.
 *
 * <p>세 경로를 전부 본다 — ARI 수신, 예약 목록 조회, 웹훅을 쏘게 만드는 시나리오.
 * 하나라도 열려 있으면 그 경로로 키 없는 어댑터가 통과한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyTest {

    @Autowired
    private TestRestTemplate rest;

    private record Endpoint(String label, HttpMethod method, String path) {
    }

    private static Stream<Arguments> 채널의_모든_경로() {
        return Stream.of(
                new Endpoint("ARI 수신", HttpMethod.POST, "/api/ari"),
                new Endpoint("예약 목록 조회", HttpMethod.GET, "/api/bookings"),
                new Endpoint("웹훅 발신(시나리오)", HttpMethod.POST, "/api/scenarios/duplicate"))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0} 는 키가 없으면 401 이다")
    @MethodSource("채널의_모든_경로")
    void 키가_없으면_401(Endpoint endpoint) {
        assertThat(call(endpoint, ApiKeys.unsigned("{}")))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest(name = "{0} 는 키가 틀리면 401 이다")
    @MethodSource("채널의_모든_경로")
    void 키가_틀리면_401(Endpoint endpoint) {
        assertThat(call(endpoint, ApiKeys.withKey("{}", "wrong-key")))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest(name = "{0} 는 키가 맞으면 통과한다")
    @MethodSource("채널의_모든_경로")
    void 키가_맞으면_통과한다(Endpoint endpoint) {
        // 401 이 아니어야 한다는 것만 본다. 각 경로가 무엇을 돌려주는지는 다른 테스트가 본다.
        assertThat(call(endpoint, ApiKeys.signed("{}")))
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("길이가 다른 키도 401 이다")
    void 짧은_키도_401() {
        // 상수 시간 비교라 길이가 달라도 같은 답이 나와야 한다.
        assertThat(rest.exchange("/api/bookings", HttpMethod.GET,
                ApiKeys.withKey(null, "x"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpStatus call(Endpoint endpoint, HttpEntity<Object> entity) {
        return HttpStatus.valueOf(rest.exchange(
                endpoint.path(), endpoint.method(), entity, String.class).getStatusCode().value());
    }
}
