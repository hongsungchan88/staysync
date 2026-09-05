package com.mockota;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** 테스트가 요청에 API 키를 싣는 자리. {@code application.yml} 의 기본값과 짝이다. */
final class ApiKeys {

    static final String VALID = "mock-ota-dev-key";

    private ApiKeys() {
    }

    static HttpEntity<Object> signed(Object body) {
        return withKey(body, VALID);
    }

    static HttpEntity<Object> withKey(Object body, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set(ApiKeyFilter.HEADER, key);
        }
        return new HttpEntity<>(body, headers);
    }

    /** 키를 아예 싣지 않는다. */
    static HttpEntity<Object> unsigned(Object body) {
        return withKey(body, null);
    }
}
