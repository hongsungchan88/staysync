package com.staysync.channel.adapter.ical;

import com.staysync.channel.port.AdapterType;
import com.staysync.channel.port.AriUpdateCommand;
import com.staysync.channel.port.BookingFeed;
import com.staysync.channel.port.Capability;
import com.staysync.channel.port.ChannelAdapter;
import com.staysync.channel.port.ChannelCredentials;
import com.staysync.channel.port.ChannelException;
import com.staysync.channel.port.InboundBooking;
import com.staysync.channel.port.SyncResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 에어비앤비 등이 발행한 iCal 을 읽어 온다. <b>레지스트리의 두 번째 등록자다.</b>
 *
 * <p>계획서 6.2 와 13.4 가 명세이고, 파서가 지킬 것은 조사-02 3절이 확정했다.
 * 13.4 의 스케치를 그대로 따르지 않은 곳이 둘 있다.
 *
 * <ul>
 *   <li><b>{@code revision(hashOf(...))} 를 쓰지 않는다.</b> 수신부가
 *       {@code in.revision() > 기존} 으로 <i>크기</i>를 비교하는데 해시에는 순서가
 *       없다. 날짜가 바뀐 뒤의 해시가 우연히 작으면 수정이 무시되고, 그때 로그는
 *       깨끗하다. {@link InboundBooking#revision()} 을 {@code null} 로 두어
 *       "값이 다르면 수정"으로 판정하게 한다(ADR 0013)</li>
 *   <li><b>{@code extractGuestName} 과 {@code isBlock} 을 만들지 않는다.</b>
 *       조사-02 가 확인한 실제 발행물의 {@code SUMMARY} 는
 *       {@code Airbnb (Not available)} 하나뿐이라 게스트도 예약/차단 구분도 없다.
 *       키워드로 나누면 <b>우리가 가진 실제 샘플로 검증할 수 없는 코드</b>가 된다</li>
 * </ul>
 *
 * <p>대량 소실 방어({@code last_event_count})는 여기가 아니라 수신부에 있다. 기준값이
 * 연결에 저장되어야 하고, 어댑터가 메모리에 들고 있으면 <b>재기동 직후 첫 폴링에
 * 방어가 없다.</b> 그 순간이 정확히 방어가 필요한 순간이다.
 *
 * <p>기능 선언은 {@link AdapterType} 에게 물어 그대로 돌려준다(10주차 결정).
 */
@Component
public class IcalAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(IcalAdapter.class);

    /** 자격 증명 키. 발행자의 내보내기 주소다. <b>비밀이다</b>(조사-02 1절). */
    public static final String ICAL_URL = "ical_url";

    /** 계획서 6.2 의 "지수 백오프 3회". 첫 시도를 포함한 횟수다. */
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient client;
    private final long backoffBaseMs;

    IcalAdapter(RestClient.Builder builder,
                @Value("${staysync.channel.ical.timeout-ms:10000}") int timeoutMs,
                @Value("${staysync.channel.ical.backoff-base-ms:500}") long backoffBaseMs) {
        this.backoffBaseMs = backoffBaseMs;
        this.client = builder.requestFactory(timeoutFactory(timeoutMs)).build();
    }

    /**
     * 타임아웃 10초(계획서 6.2).
     *
     * <p>기본 무한 대기면 응답하지 않는 발행자 하나가 폴링 주기를 통째로 잡아먹고,
     * 뒤의 연결이 그 주기를 잃는다.
     */
    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory(int timeoutMs) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return factory;
    }

    @Override
    public AdapterType type() {
        return AdapterType.ICAL;
    }

    @Override
    public Set<Capability> capabilities() {
        return type().capabilities();
    }

    /**
     * iCal 로는 재고도 요금도 보낼 수 없다.
     *
     * <p>조용히 성공을 돌려주지 않는다. {@link AdapterType#ICAL} 이 {@code PUSH_*} 를
     * 선언하지 않으므로 여기까지 왔다면 부르는 쪽이 기능 확인을 빠뜨린 것이고,
     * 성공으로 답하면 그 결함이 <b>채널에 옛 값이 남은 채</b> 묻힌다.
     */
    @Override
    public SyncResult pushAri(ChannelCredentials credentials, AriUpdateCommand command) {
        throw new UnsupportedOperationException(
                "iCal 은 재고·요금 전송을 지원하지 않습니다. connectionId=" + credentials.connectionId());
    }

    /**
     * 발행물을 받아 파싱한다.
     *
     * <p>{@code knownEtag} 가 있으면 {@code If-None-Match} 로 조건부 요청을 보낸다.
     * 304 면 본문이 없으므로 <b>파싱하지 않고</b> {@link BookingFeed#notModified()} 를
     * 돌려준다. 15분마다 도는 폴링이 대부분 이 경로로 끝난다.
     */
    @Override
    public BookingFeed pullBookings(ChannelCredentials credentials, String knownEtag) {
        String url = credentials.require(ICAL_URL);
        ResponseEntity<String> response = fetch(url, knownEtag, credentials.connectionId());

        if (response.getStatusCode().value() == HttpStatus.NOT_MODIFIED.value()) {
            return BookingFeed.notModified();
        }
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            // 200 인데 본문이 비었다. "바뀐 것 없음"으로 다루지 않는다 —
            // 발행물이 통째로 사라진 경우와 구분되지 않으므로 대량 소실 방어에 넘긴다.
            return BookingFeed.of(response.getHeaders().getETag(), List.of());
        }

        List<InboundBooking> bookings = new ArrayList<>();
        for (IcalParser.VEvent event : IcalParser.parse(body)) {
            bookings.add(toInbound(event));
        }
        return BookingFeed.of(response.getHeaders().getETag(), bookings);
    }

    /**
     * 하나의 {@code VEVENT} 를 우리 형태로 옮긴다.
     *
     * <p><b>{@code DTEND} 를 그대로 체크아웃일로 쓴다.</b> iCal 의 {@code DTEND} 는
     * 배타적이라 {@code 20270906} 이면 9월 5일까지 막힌 것이고, 하루를 빼면 매 예약이
     * 하루씩 밀린다. 화면은 정상으로 보인다(조사-02 3절).
     *
     * <p>게스트 정보는 비운다. 판매 단위 식별자도 비운다 — 발행물에 상품 식별자가
     * 없어 수신부가 그 연결의 매핑으로 채운다.
     */
    private static InboundBooking toInbound(IcalParser.VEvent event) {
        return new InboundBooking(
                event.uid(), null, event.start(), event.endExclusive(),
                null, 0, 0, BigDecimal.ZERO,
                null,      // revision 이 없는 채널이다. 값이 다르면 수정으로 본다
                false,     // 취소 통지가 없다. 목록에서 사라지는 것이 취소다
                null);
    }

    /**
     * 조건부 GET. 일시 오류면 지수 백오프로 다시 시도한다(계획서 6.2).
     *
     * <p>폴링 주기(15분)가 이미 재시도이지만, 그 주기를 기다리면 예약 하나가 최대
     * 15분 늦게 들어온다. 짧은 백오프 세 번이 그 사이를 메운다. 영구 오류는 다시
     * 보내도 같은 답이 오므로 즉시 올린다.
     *
     * <p><b>URL 을 로그에 남기지 않는다.</b> 주소를 아는 사람은 누구나 그 리스팅의
     * 예약 일정을 읽는다(조사-02 1절). 연결 식별자만 남긴다.
     */
    private ResponseEntity<String> fetch(String url, String knownEtag, Long connectionId) {
        ChannelException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return client.get()
                        .uri(url)
                        .headers(headers -> {
                            if (knownEtag != null && !knownEtag.isBlank()) {
                                headers.setIfNoneMatch(knownEtag);
                            }
                        })
                        .retrieve()
                        .onStatus(status -> status.isError(), (request, response) -> {
                            throw translate(response.getStatusCode().value());
                        })
                        .toEntity(String.class);
            } catch (ResourceAccessException e) {
                last = new ChannelException.TransientChannelException(
                        "iCal 발행물에 닿지 못했습니다: " + e.getMessage(), e);
            } catch (ChannelException.TransientChannelException e) {
                last = e;
            }
            if (attempt < MAX_ATTEMPTS) {
                log.debug("iCal 수신을 다시 시도한다. connectionId={} 시도={}", connectionId, attempt);
                sleep(backoffBaseMs << (attempt - 1));
            }
        }
        throw last == null
                ? new ChannelException.TransientChannelException("iCal 수신에 실패했습니다.")
                : last;
    }

    /** 상태 코드를 우리 세 갈래로 옮긴다. {@code MockOtaAdapter} 와 같은 규칙이다. */
    private static ChannelException translate(int status) {
        if (status == 429) {
            return new ChannelException.RateLimitedException(Duration.ofSeconds(60));
        }
        if (status >= 500) {
            return new ChannelException.TransientChannelException("iCal 발행자가 " + status + " 로 답했습니다.");
        }
        // 404(주소 바뀜), 403(비공개 전환), 401. 다시 받아도 같은 답이 온다.
        return new ChannelException.PermanentChannelException("iCal 발행자가 " + status + " 로 답했습니다.");
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException.TransientChannelException("iCal 수신이 중단됐습니다.");
        }
    }
}
