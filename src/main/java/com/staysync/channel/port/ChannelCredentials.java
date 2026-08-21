package com.staysync.channel.port;

import java.util.Map;

/**
 * 채널 접속에 필요한 정보. 데이터베이스에는 암호화해 저장하고
 * 어댑터에 넘길 때만 복호화한다.
 *
 * @param connectionId 채널 연결 식별자
 * @param channelCode  채널 코드 (AIRBNB_ICAL 등)
 * @param values       iCal URL, API 키 등
 */
public record ChannelCredentials(Long connectionId, String channelCode, Map<String, String> values) {

    public String require(String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "채널 설정에 %s 가 없습니다. channelCode=%s".formatted(key, channelCode));
        }
        return value;
    }

    public String get(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }
}
