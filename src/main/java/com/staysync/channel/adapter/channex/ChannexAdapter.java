package com.staysync.channel.adapter.channex;

import com.fasterxml.jackson.databind.JsonNode;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.port.AriUpdateCommand;
import com.staysync.channel.port.Capability;
import com.staysync.channel.port.ChannelAdapter;
import com.staysync.channel.port.ChannelAriDay;
import com.staysync.channel.port.ChannelCredentials;
import com.staysync.channel.port.BookingFeed;
import com.staysync.channel.port.ChannelException;
import com.staysync.channel.port.InboundBooking;
import com.staysync.channel.port.SyncResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Channex 를 두드리는 어댑터. <b>레지스트리의 세 번째 등록자다</b>(작업지시-17).
 *
 * <p>P3 의 "세 구현을 감춘다"가 실제가 되는 자리다. 브랜치 셋으로 붙였다 — 연결·매핑,
 * 재고·요금 전송, 예약 피드. <b>선언은 구현된 것만 한다</b>(2절 E): {@code PUSH_*} 셋과
 * {@code PULL_BOOKING}. 웹훅은 없다(5절 4번 — 피드 + ack).
 *
 * <h2>예약 수신 — 리비전 피드 + ack (스테이징 실측, 확인-10 3절)</h2>
 *
 * <ul>
 *   <li>{@code GET /booking_revisions/feed?filter[property_id]=…} 가 확인 안 된 리비전을
 *       {@code inserted_at} 오름차순으로 준다. 상태는 {@code new}·{@code modified}·
 *       {@code cancelled}. 식별자는 {@code booking_id}(예약)와 {@code id}(리비전, UUID)</li>
 *   <li><b>수정 순서는 {@code inserted_at} 의 epoch 초</b>를 {@code revision} 에 넣어 판정한다.
 *       UUID 는 크기 비교가 안 되고, ADR 0013 의 "값이 달라졌는지" 판정은 ack 실패 뒤 옛
 *       리비전이 새 것 뒤에 다시 오면 되돌린다. INT 라 2038-01-19 까지다</li>
 *   <li><b>금액은 이 채널이 준다</b>(9.2 A). {@code amount} 를 그대로 싣고, 없으면 미상
 *       ({@code null})로 두고 경고를 남긴다 — 0 으로 바꾸지 않는다</li>
 *   <li><b>통화가 숙소 통화와 다르면 넣지 않는다</b>(2절 C). 숫자만 옮기면 화면이 "130원"
 *       인데 실제는 130 USD 다. 그 리비전은 ack 하지 않고 경고를 남긴다 — Channex 가 30분
 *       동안 다시 주고 메일을 보내므로 사람 눈에 닿는다</li>
 *   <li><b>ack 는 처리가 커밋된 뒤</b> 폴러가 {@link #acknowledge} 로 한다. 리비전 식별자는
 *       {@link InboundBooking#rawPayload()} 에 실어 둔 리비전 JSON 에서 꺼낸다</li>
 * </ul>
 *
 * <p>자격 증명은 둘이다 — {@link #API_KEY}(비밀, {@code user-api-key} 헤더)와
 * {@link #PROPERTY_ID}(Channex 숙소 식별자, 비밀 아님). 둘 다 연결을 만들 때
 * {@code ChannelConnectionService} 가 있는지 본다. 주소는 연결이 아니라 설정이다
 * ({@code staysync.channel.channex.base-url}, 기본 스테이징) — 스테이징/운영은 배포
 * 단위로 갈리지 연결마다 갈리지 않는다. {@link #BASE_URL} 은 테스트 스텁이 넣는
 * 예외이고 화면은 보내지 않는다.
 *
 * <h2>Channex 가 실제로 하는 일 (스테이징 실측, 확인-10 2절)</h2>
 *
 * <ul>
 *   <li><b>재고와 요금·제약은 다른 끝점이다.</b> {@code POST /availability} 는
 *       {@code room_type_id} 로, {@code POST /restrictions} 는 {@code rate_plan_id} 로
 *       받는다. 한 세그먼트에 둘이 섞여 오면(가용 + 판매중지) 여기서 갈라 두 번 보낸다.
 *       {@code stop_sell} 은 제약이라 요금제 쪽이다</li>
 *   <li><b>잘못된 값은 200 과 경고로 온다.</b> 지난 날짜, 0 요금, 음수 재고, 없는
 *       객실, 빈 본문까지 전부 {@code 200 {"data":[],"meta":{"warnings":[…]}}} 다. 400 은
 *       못 봤다. 경고가 하나라도 있으면 <b>그 값은 반영되지 않은 것</b>이고 다시 보내도
 *       같은 답이라 {@link ChannelException.PermanentChannelException} 이다(5절 5번).
 *       {@code data} 에 task 가 있어도 성공으로 닫지 않는다</li>
 *   <li><b>요금은 소수 문자열이다.</b> {@code "120.00"}. 정수는 통화 최소 단위로 읽혀
 *       통화마다 자릿수가 달라 100배가 틀린다(2절 B)</li>
 *   <li><b>되읽기는 {@code GET /restrictions} 하나로 된다.</b> 요금제 키 아래 날짜마다
 *       {@code availability}·{@code rate}·{@code min_stay_arrival}·{@code stop_sell} 이
 *       같이 온다. 재동기화가 이걸로 대조한다</li>
 *   <li>레이트 리밋은 429 + {@code Retry-After} 로 가정하되, 스테이징의
 *       {@code ratelimit-policy} 헤더는 분당 6,000 이라 실제 429 는 못 봤다. 없으면
 *       {@code ratelimit: "availability";r=…;t=NN} 의 {@code t}(초), 그것도 없으면 60초</li>
 * </ul>
 *
 * <p><b>API 키는 어디에도 찍지 않는다.</b> 예외 메시지에 요청 헤더나 URL 이 실리지
 * 않는지 본다(4절 — iCal 주소가 실리던 {@code a7c9df2} 와 같은 자리). 경고 본문은
 * 식별자와 값뿐이라 그대로 싣는다.
 */
@Component
public class ChannexAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(ChannexAdapter.class);

    /** 자격 증명 키. 값은 {@code user-api-key} 헤더로 나간다. <b>비밀이다.</b> */
    public static final String API_KEY = "api_key";

    /** 자격 증명 키. Channex 쪽 숙소 식별자(UUID). 비밀은 아니지만 연결의 일부다. */
    public static final String PROPERTY_ID = "property_id";

    /** 테스트 스텁이 주소를 바꿔 끼우는 자리. 화면은 이 키를 보내지 않는다. */
    static final String BASE_URL = "base_url";

    static final String HEADER_API_KEY = "user-api-key";

    /** {@code ratelimit: "availability";r=5996;t=21} 의 {@code t} — 창이 다시 열리기까지의 초. */
    private static final Pattern RATELIMIT_RESET = Pattern.compile(";t=(\\d+)");

    /** 리비전 피드 한 번에 받는 수. 남으면 다음 주기에 이어 받는다. */
    static final int FEED_LIMIT = 100;

    private final RestClient client;
    private final String defaultBaseUrl;
    private final ChannexSendLimiter limiter = new ChannexSendLimiter();

    /** Channex 숙소의 통화. 바뀌지 않는 값이라 한 번 읽고 든다. */
    private final Map<String, String> currencyByProperty = new ConcurrentHashMap<>();

    ChannexAdapter(RestClient.Builder builder,
                   @Value("${staysync.channel.channex.base-url:https://staging.channex.io}")
                   String defaultBaseUrl,
                   @Value("${staysync.channel.channex.timeout-ms:10000}") int timeoutMs) {
        this.defaultBaseUrl = defaultBaseUrl;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.client = builder.requestFactory(factory).build();
    }

    @Override
    public AdapterType type() {
        return AdapterType.CHANNEX;
    }

    @Override
    public Set<Capability> capabilities() {
        return type().capabilities();
    }

    // --- 우리 → 채널 ------------------------------------------------------------

    /**
     * 재고는 {@code /availability} 로, 요금·제약은 {@code /restrictions} 로. 둘 다 있으면
     * 두 번 나간다 — Channex 권고이고 끝점이 실제로 다르다.
     *
     * @return 반영된 값의 수(두 본문의 항목 합). 경고가 있으면 예외다
     */
    @Override
    public SyncResult pushAri(ChannelCredentials credentials, AriUpdateCommand command) {
        String propertyId = credentials.require(PROPERTY_ID);
        List<Map<String, Object>> availability = new ArrayList<>();
        List<Map<String, Object>> restrictions = new ArrayList<>();

        for (AriUpdateCommand.Segment segment : command.segments()) {
            if (segment.availability() != null) {
                Map<String, Object> value = base(propertyId, segment);
                value.put("room_type_id", command.externalUnitId());
                value.put("availability", segment.availability());
                availability.add(value);
            }
            Map<String, Object> restriction = restrictionOf(segment);
            if (!restriction.isEmpty()) {
                if (command.externalRateId() == null || command.externalRateId().isBlank()) {
                    // 요금제 없는 매핑은 연결 서비스가 막지만, 옛 행이 남아 있을 수 있다.
                    // 다시 보내도 같다 — 사람이 매핑에 요금제를 넣어야 한다.
                    throw new ChannelException.PermanentChannelException(
                            "Channex 매핑에 요금제 식별자가 없어 요금·제약을 보낼 수 없습니다. room_type_id="
                                    + command.externalUnitId());
                }
                Map<String, Object> value = base(propertyId, segment);
                value.put("rate_plan_id", command.externalRateId());
                value.putAll(restriction);
                restrictions.add(value);
            }
        }

        int applied = 0;
        if (!availability.isEmpty()) {
            applied += post(credentials, "/api/v1/availability", availability);
        }
        if (!restrictions.isEmpty()) {
            applied += post(credentials, "/api/v1/restrictions", restrictions);
        }
        return SyncResult.ok(applied);
    }

    private static Map<String, Object> base(String propertyId, AriUpdateCommand.Segment segment) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("property_id", propertyId);
        value.put("date_from", segment.from().toString());
        value.put("date_to", segment.to().toString());
        return value;
    }

    /** 세그먼트에서 제약 쪽 값만. 비어 있으면 이 세그먼트는 재고만 바꾼 것이다. */
    private static Map<String, Object> restrictionOf(AriUpdateCommand.Segment segment) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (segment.rate() != null) {
            value.put("rate", money(segment.rate()));
        }
        if (segment.minStay() != null) {
            // 숙소의 min_stay_type 이 both 라 둘 다 같은 값으로. 하나만 보내면 나머지가
            // 옛 값으로 남아 채널이 더 긴 쪽을 적용한다.
            value.put("min_stay_arrival", segment.minStay());
            value.put("min_stay_through", segment.minStay());
        }
        if (segment.maxStay() != null) {
            value.put("max_stay", segment.maxStay());
        }
        if (segment.closedToArrival() != null) {
            value.put("closed_to_arrival", segment.closedToArrival());
        }
        if (segment.closedToDeparture() != null) {
            value.put("closed_to_departure", segment.closedToDeparture());
        }
        if (segment.stopSell() != null) {
            value.put("stop_sell", segment.stopSell());
        }
        return value;
    }

    /** {@code "120.00"}. 정수로 보내면 최소 단위로 읽혀 통화마다 다르게 틀린다. */
    static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 본문을 보내고 경고를 읽는다.
     *
     * @return 보낸 값의 수. 경고가 하나라도 있으면 던진다 — 성공으로 닫지 않는다(5절 5번)
     */
    private int post(ChannelCredentials credentials, String path, List<Map<String, Object>> values) {
        // 우리 쪽 상한(문서 값)이 채널보다 먼저다. 넘으면 채널에 닿지 않고 워커가 미룬다.
        Duration wait = limiter.tryAcquire(credentials.require(PROPERTY_ID) + ":" + path, Instant.now());
        if (wait != null) {
            throw new ChannelException.RateLimitedException(wait);
        }
        JsonNode response = exchange(credentials, path, Map.of("values", values));
        JsonNode warnings = response.path("meta").path("warnings");
        if (warnings.isArray() && !warnings.isEmpty()) {
            String summary = describe(warnings);
            log.warn("Channex 가 200 으로 답했지만 경고를 남겼다 — 그 값은 반영되지 않았다. "
                    + "path={} 경고수={} {}", path, warnings.size(), summary);
            throw new ChannelException.PermanentChannelException(
                    "Channex 가 값을 거부했습니다(" + warnings.size() + "건): " + summary);
        }
        // 한 줄은 남긴다 — "30일 변경이 요청 몇 번인가"(완료 조건 5)를 운영 로그로 센다.
        log.info("Channex 전송. path={} 값={}건 task={}", path, values.size(),
                response.path("data").path(0).path("id").asText("?"));
        return values.size();
    }

    /**
     * 경고를 한 줄로. 모양이 둘이다 — {@code {"warning": {"rate": ["must be > 0"]}, "date": …}}
     * 와 {@code {"warning": "Not found room_type for this change", …}}.
     */
    static String describe(JsonNode warnings) {
        StringBuilder out = new StringBuilder();
        Iterator<JsonNode> it = warnings.elements();
        int shown = 0;
        while (it.hasNext() && shown < 3) {
            JsonNode entry = it.next();
            if (shown > 0) {
                out.append("; ");
            }
            JsonNode warning = entry.path("warning");
            if (warning.isTextual()) {
                out.append(warning.asText());
            } else {
                Iterator<Map.Entry<String, JsonNode>> fields = warning.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    out.append(field.getKey()).append(' ').append(field.getValue().toString()).append(' ');
                }
            }
            String date = entry.path("date").asText(null);
            if (date == null) {
                date = entry.path("date_from").asText(null);
            }
            if (date != null) {
                out.append("(").append(date).append(")");
            }
            shown++;
        }
        if (warnings.size() > shown) {
            out.append(" 외 ").append(warnings.size() - shown).append("건");
        }
        return out.toString().trim();
    }

    // --- 채널 → 우리 (재동기화) ---------------------------------------------------

    /**
     * 요금제가 있으면 {@code GET /restrictions} 한 번으로 재고·요금·최소 숙박·판매중지를
     * 함께 읽는다. 없으면 {@code GET /availability} 로 재고만.
     */
    @Override
    public List<ChannelAriDay> fetchAriSnapshot(ChannelCredentials credentials,
                                                String externalUnitId, String externalRateId,
                                                LocalDate from, LocalDate to) {
        String propertyId = credentials.require(PROPERTY_ID);
        Map<LocalDate, ChannelAriDay> byDate = new TreeMap<>();
        if (externalRateId != null && !externalRateId.isBlank()) {
            JsonNode days = get(credentials, "/api/v1/restrictions?filter[property_id]=" + propertyId
                    + "&filter[date][gte]=" + from + "&filter[date][lte]=" + to
                    + "&filter[restrictions]=availability,rate,min_stay_arrival,stop_sell")
                    .path("data").path(externalRateId);
            Iterator<Map.Entry<String, JsonNode>> it = days.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> day = it.next();
                JsonNode v = day.getValue();
                LocalDate date = LocalDate.parse(day.getKey());
                Integer availability = v.hasNonNull("availability") ? v.get("availability").asInt() : null;
                // 재고가 0 인 날은 Channex 가 stop_sell 을 스스로 켠다(실측, 확인-10 4절). 그날의
                // 판매중지는 "모른다"로 둔다 — 아니면 매일 새벽 매진일마다 거짓 차이가 나서
                // 우리 false 를 다시 보내고, 채널은 또 true 로 돌려놓는다. 매진일에 판매중지는 뜻이 없다.
                Boolean stopSell = v.hasNonNull("stop_sell") && !(availability != null && availability == 0)
                        ? v.get("stop_sell").asBoolean() : null;
                byDate.put(date, new ChannelAriDay(date, availability,
                        v.hasNonNull("rate") ? new BigDecimal(v.get("rate").asText()) : null,
                        v.hasNonNull("min_stay_arrival") ? v.get("min_stay_arrival").asInt() : null,
                        stopSell));
            }
            return List.copyOf(byDate.values());
        }
        JsonNode days = get(credentials, "/api/v1/availability?filter[property_id]=" + propertyId
                + "&filter[date][gte]=" + from + "&filter[date][lte]=" + to)
                .path("data").path(externalUnitId);
        Iterator<Map.Entry<String, JsonNode>> it = days.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> day = it.next();
            LocalDate date = LocalDate.parse(day.getKey());
            byDate.put(date, new ChannelAriDay(date, day.getValue().asInt(), null, null, null));
        }
        return List.copyOf(byDate.values());
    }

    @Override
    public List<ChannelAriDay> fetchAriSnapshot(ChannelCredentials credentials, String externalUnitId,
                                                LocalDate from, LocalDate to) {
        return fetchAriSnapshot(credentials, externalUnitId, null, from, to);
    }

    // --- 채널 → 우리 (예약 피드) ------------------------------------------------------

    /**
     * 확인 안 된 리비전을 가져온다. 조건부 요청은 없다({@code knownEtag} 안 씀).
     *
     * <p>리비전 하나가 예약 하나다. 방이 여럿인 예약은 방마다 하나로 가른다(우리 예약은
     * 판매 단위 하나) — 둘째부터 식별자에 {@code #2} 를 붙인다. 통화가 다른 리비전은
     * <b>싣지 않는다</b>(ack 도 안 된다).
     */
    @Override
    public BookingFeed pullBookings(ChannelCredentials credentials, String knownEtag) {
        String propertyId = credentials.require(PROPERTY_ID);
        String expected = currencyOf(credentials, propertyId);
        JsonNode data = get(credentials, "/api/v1/booking_revisions/feed?filter[property_id]=" + propertyId
                + "&pagination[limit]=" + FEED_LIMIT + "&order[inserted_at]=asc").path("data");

        List<JsonNode> revisions = new ArrayList<>();
        data.forEach(revisions::add);
        // 피드는 inserted_at 오름차순으로 온다고 실측됐지만(확인-10 4절) 한 묶음 안에서도 정렬한다.
        revisions.sort(java.util.Comparator.comparing(r -> r.path("attributes").path("inserted_at").asText("")));

        List<InboundBooking> bookings = new ArrayList<>();
        for (JsonNode revision : revisions) {
            JsonNode a = revision.path("attributes");
            String currency = a.path("currency").asText(null);
            if (currency != null && expected != null && !currency.equalsIgnoreCase(expected)) {
                log.warn("Channex 리비전의 통화가 숙소 통화와 달라 넣지 않는다. 사람이 봐야 한다. "
                                + "revisionId={} bookingId={} 통화={} 숙소={}",
                        a.path("id").asText(), a.path("booking_id").asText(), currency, expected);
                continue;
            }
            bookings.addAll(toInbound(revision));
        }
        return BookingFeed.of(bookings);
    }

    /** 리비전 하나 → 방마다 하나. */
    static List<InboundBooking> toInbound(JsonNode revision) {
        JsonNode a = revision.path("attributes");
        String bookingId = a.path("booking_id").asText();
        boolean cancelled = "cancelled".equalsIgnoreCase(a.path("status").asText());
        Integer version = epochSeconds(a.path("inserted_at").asText(null));
        String guest = (a.path("customer").path("name").asText("") + " "
                + a.path("customer").path("surname").asText("")).trim();
        if (guest.isEmpty()) {
            guest = null;
        }
        JsonNode rooms = a.path("rooms");
        int roomCount = rooms.isArray() ? rooms.size() : 0;
        String raw = revision.toString();

        List<InboundBooking> result = new ArrayList<>();
        if (roomCount == 0) {
            // 방 정보 없는 리비전(취소 통지가 그럴 수 있다). 예약 날짜로 넣는다.
            result.add(new InboundBooking(bookingId, null,
                    date(a, "arrival_date"), date(a, "departure_date"), guest, 2, 0,
                    money(a.path("amount"), bookingId), version, cancelled, raw));
            return result;
        }
        for (int i = 0; i < roomCount; i++) {
            JsonNode room = rooms.get(i);
            String id = i == 0 ? bookingId : bookingId + "#" + (i + 1);
            // 방이 하나면 예약 총액(세금·수수료 포함), 여럿이면 방마다의 금액.
            JsonNode amount = roomCount == 1 ? a.path("amount") : room.path("amount");
            result.add(new InboundBooking(id, room.path("room_type_id").asText(null),
                    date(room, "checkin_date"), date(room, "checkout_date"), guest,
                    room.path("occupancy").path("adults").asInt(2),
                    room.path("occupancy").path("children").asInt(0),
                    money(amount, id), version, cancelled, raw));
        }
        return result;
    }

    /** {@code "2026-09-20T15:45:30.575064"}(UTC, 존 없음) → epoch 초. 없으면 {@code null}(값 비교 판정). */
    static Integer epochSeconds(String insertedAt) {
        if (insertedAt == null || insertedAt.isBlank()) {
            return null;
        }
        try {
            return (int) LocalDateTime.parse(insertedAt).toEpochSecond(ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** 금액이 없으면 미상. 0 으로 바꾸지 않는다(9.2 A, 작업지시-16). */
    private static BigDecimal money(JsonNode amount, String bookingId) {
        if (amount == null || amount.isMissingNode() || amount.isNull() || amount.asText().isBlank()) {
            log.warn("Channex 리비전에 금액이 없어 미상으로 둔다. 0 원이 아니다. bookingId={}", bookingId);
            return null;
        }
        return new BigDecimal(amount.asText());
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null ? null : LocalDate.parse(value);
    }

    /**
     * 처리가 커밋된 뒤 리비전을 확인한다. 실패는 던진다 — 폴러가 로그만 남기고 넘어가며
     * 같은 리비전이 다음 주기에 다시 온다(멱등).
     */
    @Override
    public void acknowledge(ChannelCredentials credentials, InboundBooking booking) {
        String revisionId = revisionIdOf(booking);
        if (revisionId == null) {
            return;
        }
        exchange(credentials, "/api/v1/booking_revisions/" + revisionId + "/ack", Map.of());
    }

    static String revisionIdOf(InboundBooking booking) {
        if (booking.rawPayload() == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(booking.rawPayload())
                    .path("attributes").path("id").asText(null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private String currencyOf(ChannelCredentials credentials, String propertyId) {
        String currency = currencyByProperty.computeIfAbsent(propertyId, id -> {
            String read = get(credentials, "/api/v1/properties/" + id)
                    .path("data").path("attributes").path("currency").asText(null);
            if (read == null) {
                log.warn("Channex 숙소의 통화를 읽지 못했다. 통화 대조 없이 넣는다. propertyId={}", id);
            }
            return read == null ? "" : read;
        });
        return currency.isEmpty() ? null : currency;
    }

    // --- HTTP -------------------------------------------------------------------

    private JsonNode exchange(ChannelCredentials credentials, String path, Object body) {
        try {
            return client.post()
                    .uri(baseUrl(credentials) + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HEADER_API_KEY, credentials.require(API_KEY))
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw translate(response.getStatusCode(), response.getHeaders().getFirst("Retry-After"),
                                response.getHeaders().getFirst("ratelimit"));
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            // 연결 실패와 타임아웃. 메시지에 주소가 실릴 수 있어 원인만 남긴다.
            throw new ChannelException.TransientChannelException(
                    "Channex 에 닿지 못했습니다: " + e.getClass().getSimpleName(), e);
        }
    }

    private JsonNode get(ChannelCredentials credentials, String pathAndQuery) {
        try {
            return client.get()
                    .uri(baseUrl(credentials) + pathAndQuery)
                    .header(HEADER_API_KEY, credentials.require(API_KEY))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw translate(response.getStatusCode(), response.getHeaders().getFirst("Retry-After"),
                                response.getHeaders().getFirst("ratelimit"));
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {
            throw new ChannelException.TransientChannelException(
                    "Channex 에 닿지 못했습니다: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 상태 코드를 우리 세 갈래로. 401(키)·403(IP 허용 목록)·404 는 다시 보내도 같다.
     * 검증 실패는 400 이 아니라 200+경고로 오므로 여기 안 온다.
     */
    private static ChannelException translate(HttpStatusCode status, String retryAfter, String ratelimit) {
        if (status.value() == 429) {
            return new ChannelException.RateLimitedException(retryAfterOf(retryAfter, ratelimit));
        }
        if (status.is5xxServerError()) {
            return new ChannelException.TransientChannelException("Channex 가 " + status.value() + " 로 답했습니다.");
        }
        return new ChannelException.PermanentChannelException("Channex 가 " + status.value() + " 로 답했습니다.");
    }

    /** {@code Retry-After}(초) → {@code ratelimit} 의 {@code t=} → 60초. */
    static Duration retryAfterOf(String retryAfter, String ratelimit) {
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                return Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
            } catch (NumberFormatException ignored) {
                // 날짜 형식이면 아래로 떨어진다.
            }
        }
        if (ratelimit != null) {
            Matcher m = RATELIMIT_RESET.matcher(ratelimit);
            if (m.find()) {
                return Duration.ofSeconds(Long.parseLong(m.group(1)));
            }
        }
        return Duration.ofSeconds(60);
    }

    private String baseUrl(ChannelCredentials credentials) {
        return credentials.get(BASE_URL, defaultBaseUrl);
    }
}
