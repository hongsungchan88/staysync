package com.staysync.channel.adapter.mock;

import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Mock OTA 시뮬레이터를 두드리는 어댑터. <b>레지스트리의 첫 등록자다.</b>
 *
 * <p>11주차에 만든 시뮬레이터(`mock-ota-simulator/`, 포트 8081)가 상대다. iCal 은
 * 요금을 동기화하지 못하고 Channex 스테이징은 남의 서버라, 지연·실패·중복·순서 역전을
 * 실제로 만들어 검증할 수 있는 채널이 이것뿐이다.
 *
 * <p><b>시뮬레이터에 맞춘다. 반대로 하지 않는다.</b> 시뮬레이터의 JSON 은
 * {@code snake_case} 이고 우리 것은 {@code camelCase} 라, 여기서 이름을 옮기는 것이
 * 어댑터의 일이다. 시뮬레이터를 우리 형식으로 고치면 검증하는 대상이 우리 흉내가 된다.
 *
 * <p><b>자격 증명을 반드시 싣는다.</b> 시뮬레이터가 {@code /api/**} 전체에서 키를
 * 검사하므로 빠뜨리면 401 로 즉시 드러난다. 11주차에 그걸 위해 넣은 검사다.
 *
 * <p>기능 선언은 {@link AdapterType} 에게 물어 그대로 돌려준다(10주차 결정). 여기에
 * 따로 적으면 화면이 보는 값과 워커가 보는 값이 갈라진다.
 */
@Component
public class MockOtaAdapter implements ChannelAdapter {

    /** 자격 증명 키. 연결마다 다른 시뮬레이터를 가리킬 수 있게 URL 도 여기에 둔다. */
    static final String API_KEY = "api_key";
    static final String BASE_URL = "base_url";
    static final String HEADER_API_KEY = "X-Api-Key";

    private final RestClient client;
    private final String defaultBaseUrl;

    MockOtaAdapter(RestClient.Builder builder,
                   @Value("${staysync.channel.mock.base-url:http://localhost:8081}")
                   String defaultBaseUrl,
                   @Value("${staysync.channel.mock.timeout-ms:3000}") int timeoutMs) {
        this.defaultBaseUrl = defaultBaseUrl;
        this.client = builder
                .requestFactory(timeoutFactory(timeoutMs))
                .build();
    }

    /**
     * 타임아웃을 짧게 둔다.
     *
     * <p>시뮬레이터의 지연 주입이 타임아웃과 서킷브레이커를 검증하라고 있는 것인데,
     * 기본 무한 대기면 지연을 아무리 넣어도 워커가 그냥 기다린다. 기다리는 동안 그
     * 연결의 뒤 작업이 전부 막힌다.
     */
    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory(int timeoutMs) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return factory;
    }

    @Override
    public AdapterType type() {
        return AdapterType.MOCK;
    }

    @Override
    public Set<Capability> capabilities() {
        return type().capabilities();
    }

    @Override
    public SyncResult pushAri(ChannelCredentials credentials, AriUpdateCommand command) {
        AriBody body = AriBody.of(command);
        exchange(credentials, "/api/ari", body);
        return SyncResult.ok(command.segments().size());
    }

    /**
     * 시뮬레이터는 조건부 요청을 하지 않는다. {@code knownEtag} 를 쓰지 않고 매번
     * 전체를 받는다 — 예약 목록이 몇 건 수준이라 아낄 것이 없다.
     *
     * <p><b>스냅샷이 아니다.</b> {@link AdapterType#MOCK} 이
     * {@code SNAPSHOT_BOOKING} 을 선언하지 않으므로, 목록에 없는 예약을 취소로
     * 다루지 않는다. 취소는 {@code status} 가 알려 준다.
     */
    @Override
    public BookingFeed pullBookings(ChannelCredentials credentials, String knownEtag) {
        MockBooking[] bookings = get(credentials, "/api/bookings", MockBooking[].class);
        return BookingFeed.of(bookings == null ? List.of() : java.util.Arrays.stream(bookings)
                .map(MockBooking::toInbound)
                .toList());
    }

    // --- HTTP -----------------------------------------------------------------

    private void exchange(ChannelCredentials credentials, String path, Object body) {
        try {
            client.post()
                    .uri(baseUrl(credentials) + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HEADER_API_KEY, credentials.require(API_KEY))
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw translate(response.getStatusCode(), response.getHeaders()
                                .getFirst("Retry-After"));
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            // 연결 실패와 타임아웃. 다음에는 될 수 있다.
            throw new ChannelException.TransientChannelException(
                    "채널에 닿지 못했습니다: " + e.getMessage(), e);
        }
    }

    private <T> T get(ChannelCredentials credentials, String path, Class<T> type) {
        try {
            return client.get()
                    .uri(baseUrl(credentials) + path)
                    .header(HEADER_API_KEY, credentials.require(API_KEY))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw translate(response.getStatusCode(), response.getHeaders()
                                .getFirst("Retry-After"));
                    })
                    .body(type);
        } catch (ResourceAccessException e) {
            throw new ChannelException.TransientChannelException(
                    "채널에 닿지 못했습니다: " + e.getMessage(), e);
        }
    }

    /**
     * 채널의 상태 코드를 우리 세 갈래로 옮긴다. <b>이 함수가 어댑터의 핵심이다.</b>
     *
     * <p>401 을 일시 오류로 분류하면 틀린 키로 8번 두드리고, 503 을 영구 오류로
     * 분류하면 잠깐의 장애에 요금 갱신을 잃는다. 채널마다 다른 해석이 여기 갇힌다.
     */
    private static ChannelException translate(HttpStatusCode status, String retryAfterHeader) {
        if (status.value() == 429) {
            return new ChannelException.RateLimitedException(parseRetryAfter(retryAfterHeader));
        }
        if (status.is5xxServerError()) {
            return new ChannelException.TransientChannelException("채널이 " + status.value() + " 로 답했습니다.");
        }
        // 401(키 틀림), 404(매핑 틀림), 400(본문 틀림). 다시 보내도 같은 답이 온다.
        return new ChannelException.PermanentChannelException("채널이 " + status.value() + " 로 답했습니다.");
    }

    /** 채널이 알려 주면 그 값을, 아니면 정책 기본값(60초)을 쓴다. */
    private static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return Duration.ofSeconds(60);
        }
        try {
            return Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return Duration.ofSeconds(60);
        }
    }

    /** 연결마다 다른 시뮬레이터를 가리킬 수 있다. 없으면 개발용 기본값이다. */
    private String baseUrl(ChannelCredentials credentials) {
        return credentials.get(BASE_URL, defaultBaseUrl);
    }

    // --- 채널 형식 ---------------------------------------------------------------

    /**
     * 시뮬레이터에 보내는 ARI 본문.
     *
     * <p>이름을 손으로 적는다. 애플리케이션의 {@code ObjectMapper} 는 {@code camelCase}
     * 라 {@link AriUpdateCommand} 를 그대로 보내면 채널이 쓰는 이름과 달라진다.
     * 채널의 형식에 맞추는 것이 어댑터의 일이다.
     */
    record AriBody(@JsonProperty("external_unit_id") String externalUnitId,
                   @JsonProperty("external_rate_id") String externalRateId,
                   @JsonProperty("segments") List<Seg> segments) {

        static AriBody of(AriUpdateCommand command) {
            return new AriBody(command.externalUnitId(), command.externalRateId(),
                    command.segments().stream().map(Seg::of).toList());
        }

        record Seg(@JsonProperty("from") LocalDate from,
                   @JsonProperty("to") LocalDate to,
                   @JsonProperty("availability") Integer availability,
                   @JsonProperty("rate") BigDecimal rate,
                   @JsonProperty("min_stay") Integer minStay,
                   @JsonProperty("stop_sell") Boolean stopSell) {

            static Seg of(AriUpdateCommand.Segment segment) {
                return new Seg(segment.from(), segment.to(), segment.availability(),
                        segment.rate(), segment.minStay(), segment.stopSell());
            }
        }
    }

    /**
     * 시뮬레이터가 돌려주는 예약.
     *
     * <p>{@code snake_case} 를 {@link InboundBooking} 으로 옮긴다. 이 매핑이 실제로
     * 도는지 보려고 시뮬레이터가 일부러 다른 형식을 쓴다.
     */
    record MockBooking(@JsonProperty("booking_id") String bookingId,
                       @JsonProperty("room_id") String roomId,
                       @JsonProperty("check_in") LocalDate checkIn,
                       @JsonProperty("check_out") LocalDate checkOut,
                       @JsonProperty("guest_name") String guestName,
                       @JsonProperty("adults") int adults,
                       @JsonProperty("children") int children,
                       @JsonProperty("total_amount") BigDecimal totalAmount,
                       @JsonProperty("revision") int revision,
                       @JsonProperty("status") String status) {

        InboundBooking toInbound() {
            return new InboundBooking(bookingId, roomId, checkIn, checkOut, guestName,
                    adults, children, totalAmount, revision,
                    "CANCELLED".equals(status), null);
        }
    }
}
