package com.staysync.channel.adapter.ical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.staysync.channel.port.ChannelCredentials;
import com.staysync.channel.port.ChannelException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * iCal 주소는 비밀이다(조사-02 1절). 예외 메시지에 실리면 폴러의 {@code log.warn} 이
 * 그대로 로그에 남긴다 — 스프링의 {@code ResourceAccessException} 은 요청 URL 을
 * 메시지에 통째로 넣는다. P5 17주차 업체 실제 피드를 붙이기 전에 막았다.
 */
class IcalAdapterLogSafetyTest {

    @Test
    @DisplayName("발행자에 닿지 못해도 예외 메시지에 주소가 실리지 않는다")
    void 연결_실패_메시지에_주소가_없다() {
        // 백오프 0 으로 세 번 시도가 바로 끝난다. 아무도 듣지 않는 포트다.
        IcalAdapter adapter = new IcalAdapter(RestClient.builder(), 300, 0);
        String secretUrl = "http://127.0.0.1:9/calendar/ical/secret-token-value.ics";

        Throwable thrown = catchThrowable(() -> adapter.pullBookings(
                new ChannelCredentials(1L, "AIRBNB_ICAL", Map.of(IcalAdapter.ICAL_URL, secretUrl)),
                null));

        assertThat(thrown).isInstanceOf(ChannelException.TransientChannelException.class);
        assertThat(thrown.toString())
                .as("폴러가 로그에 남기는 것은 toString() 이다")
                .doesNotContain("secret-token-value")
                .doesNotContain("127.0.0.1");
    }
}
