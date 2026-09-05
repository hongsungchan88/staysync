package com.mockota;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 예약이 생기면 설정된 URL 로 POST 한다. 푸시 수신 경로 검증용이다.
 *
 * <p><b>받을 곳은 아직 없다.</b> 우리 앱의 웹훅 수신 엔드포인트는 12~13주차다. 그래서
 * 지금 확인할 수 있는 것은 "지정한 곳으로 실제로 보내는가"까지이고, 테스트는 받는 쪽을
 * 직접 세워서 그걸 본다.
 *
 * <p><b>본문을 직접 직렬화한다.</b> 객체를 그대로 넘기면 {@link RestClient} 가 자기
 * 기본 변환기를 쓰는데, 그건 애플리케이션이 설정한 {@link ObjectMapper} 가 아니다.
 * 그러면 폴링 응답은 {@code snake_case} 에 ISO 날짜인데 웹훅은 {@code camelCase} 에
 * 날짜가 배열로 나간다 — <b>같은 예약이 경로마다 다른 형식으로 나가고</b>, 12주차
 * 어댑터가 매핑을 두 벌 갖게 된다. 테스트가 잡아낸 자리다.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final String url;
    private final String apiKey;
    private final ObjectMapper json;
    private final RestClient client = RestClient.create();

    WebhookSender(MockOtaProperties properties, ObjectMapper json) {
        this.url = properties.webhookUrl();
        this.apiKey = properties.apiKey();
        this.json = json;
    }

    public void send(MockBooking booking) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            client.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 실제 채널이 그러듯 자기 키를 실어 보낸다. 12~13주차의 수신
                    // 엔드포인트가 발신자를 확인할 수 있어야 한다.
                    .header(ApiKeyFilter.HEADER, apiKey)
                    .body(json.writeValueAsString(booking))
                    .retrieve()
                    .toBodilessEntity();
        } catch (JsonProcessingException e) {
            // 이건 설정 오류다. 조용히 넘기면 웹훅이 영원히 나가지 않는다.
            throw new IllegalStateException("웹훅 본문을 직렬화하지 못했습니다.", e);
        } catch (RuntimeException e) {
            // 받는 쪽이 죽어 있다고 채널이 예약을 못 만드는 것은 아니다. 실제 OTA 도
            // 그렇게 굴고, 여기서 터뜨리면 시나리오 호출이 웹훅 대상 상태에 묶인다.
            log.warn("웹훅 전송 실패. url={} bookingId={} 사유={}", url, booking.bookingId(), e.toString());
        }
    }
}
