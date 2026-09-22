package com.staysync.channel.adapter.channex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.staysync.booking.BookingService;
import com.staysync.booking.ChannelBookingCommand;
import com.staysync.booking.ChannelBookingIntake;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.SyncJob;
import com.staysync.channel.domain.SyncJobStatus;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.port.AriUpdateCommand;
import com.staysync.channel.port.ChannelAriDay;
import com.staysync.channel.port.ChannelCredentials;
import com.staysync.channel.port.ChannelException;
import com.staysync.channel.support.ChannexStubServer;
import com.staysync.channel.support.ChannexStubServer.Reply;
import com.staysync.channel.support.ChannexStubServer.Responses;
import com.staysync.channel.support.SyncTestBase;
import com.staysync.booking.calendar.BulkEdit;
import com.staysync.booking.calendar.BulkEditService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestClient;

/**
 * 작업지시-17 브랜치 2 — 재고·요금 전송. 완료 조건 5·6·7 과 9.2 B.
 *
 * <p>어댑터는 진짜, 상대는 {@link ChannexStubServer}. <b>응답 본문은 스테이징에서 실제로
 * 받은 것</b>이다(4절 — 손으로 적은 응답은 가정이다). 완료 조건 3·4(스테이징에 실제로
 * 반영됐는지)는 테스트가 아니라 확인-10 2절의 실측이다.
 */
class ChannexAriTest extends SyncTestBase {

    private static final String PROPERTY = "17e754e7-9aa8-456a-ad0a-94e1d54bc8f3";
    private static final String ROOM = "92f88770-4d38-4ff8-839d-542672d92c3e";
    private static final String RATE = "46b69549-6f8b-4a55-b594-979850f45379";

    private static ChannexStubServer channex;
    private static ChannexAdapter adapter;

    @Autowired
    private BulkEditService bulkEdit;

    @Autowired
    private BookingService booking;

    @Autowired
    private ChannelBookingIntake intake;

    @Autowired
    private com.staysync.booking.UnitCapacityService capacity;

    @Autowired
    private ObjectMapper json;

    @BeforeAll
    static void 스텁을_연다() {
        channex = new ChannexStubServer();
        adapter = new ChannexAdapter(RestClient.builder(), channex.baseUrl(), 3000);
    }

    @AfterAll
    static void 스텁을_닫는다() {
        channex.close();
    }

    @BeforeEach
    void 스텁을_비운다() {
        channex.reset();
        channex.replyAll(Reply.ok(Responses.TASK_ACCEPTED));
    }

    // --- 본문 모양 ---------------------------------------------------------------

    @Test
    @DisplayName("재고는 /availability 로, 요금·제약은 /restrictions 로 — 한 세그먼트가 둘로 갈린다. 요금은 소수 문자열")
    void 재고와_제약을_다른_끝점으로_보낸다() throws Exception {
        AriUpdateCommand command = new AriUpdateCommand(ROOM, RATE, List.of(
                new AriUpdateCommand.Segment(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 25),
                        1, new BigDecimal("120"), 2, null, null, null, false)));

        adapter.pushAri(credentials(), command);

        List<ChannexStubServer.Received> sent = channex.received();
        assertThat(sent).extracting(ChannexStubServer.Received::pathAndQuery)
                .containsExactly("/api/v1/availability", "/api/v1/restrictions");
        assertThat(sent).extracting(ChannexStubServer.Received::apiKey).containsOnly("probe-key");

        JsonNode availability = json.readTree(sent.get(0).body()).path("values").get(0);
        assertThat(availability.get("property_id").asText()).isEqualTo(PROPERTY);
        assertThat(availability.get("room_type_id").asText()).isEqualTo(ROOM);
        assertThat(availability.get("date_from").asText()).isEqualTo("2026-10-22");
        assertThat(availability.get("date_to").asText()).isEqualTo("2026-10-25");
        assertThat(availability.get("availability").asInt()).isEqualTo(1);
        assertThat(availability.has("rate")).as("재고 본문에 요금이 섞이지 않는다").isFalse();

        JsonNode restriction = json.readTree(sent.get(1).body()).path("values").get(0);
        assertThat(restriction.get("rate_plan_id").asText()).isEqualTo(RATE);
        // 정수 12000 은 통화 최소 단위로 읽혀 USD 면 120.00, JPY 면 12000 이 된다. 문자열이어야 한다.
        assertThat(restriction.get("rate").isTextual()).isTrue();
        assertThat(restriction.get("rate").asText()).isEqualTo("120.00");
        assertThat(restriction.get("min_stay_arrival").asInt()).isEqualTo(2);
        assertThat(restriction.get("min_stay_through").asInt()).isEqualTo(2);
        assertThat(restriction.get("stop_sell").asBoolean()).isFalse();
        assertThat(restriction.has("availability")).as("제약 본문에 재고가 섞이지 않는다").isFalse();
    }

    @Test
    @DisplayName("재고만 바뀐 세그먼트는 /availability 한 번, 요금만 바뀐 세그먼트는 /restrictions 한 번")
    void 바뀐_쪽만_나간다() {
        adapter.pushAri(credentials(), new AriUpdateCommand(ROOM, RATE, List.of(
                new AriUpdateCommand.Segment(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22),
                        0, null, null, null, null, null, null))));
        assertThat(channex.received()).extracting(ChannexStubServer.Received::pathAndQuery)
                .containsExactly("/api/v1/availability");

        channex.reset();
        adapter.pushAri(credentials(), new AriUpdateCommand(ROOM, RATE, List.of(
                new AriUpdateCommand.Segment(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22),
                        null, new BigDecimal("99.5"), null, null, null, null, null))));
        assertThat(channex.received()).extracting(ChannexStubServer.Received::pathAndQuery)
                .containsExactly("/api/v1/restrictions");
        assertThat(channex.received().get(0).body()).contains("\"rate\":\"99.50\"");
    }

    // --- 완료 조건 7. 200 + 경고 = 실패 -------------------------------------------

    @Test
    @DisplayName("200 이어도 경고가 있으면 영구 실패다 — 스테이징의 실제 경고 응답 셋")
    void 경고는_성공이_아니다() {
        for (String body : List.of(Responses.WARNING_RATE_ZERO, Responses.WARNING_PAST_DATE,
                Responses.WARNING_ROOM_NOT_FOUND)) {
            channex.replyAll(Reply.ok(body));
            assertThatThrownBy(() -> adapter.pushAri(credentials(), oneDay()))
                    .as("응답 %s", body)
                    .isInstanceOf(ChannelException.PermanentChannelException.class)
                    .hasMessageContaining("거부");
        }
        // 경고의 두 모양이 메시지에 실린다 — 사람이 last_error 로 원인을 본다.
        channex.replyAll(Reply.ok(Responses.WARNING_RATE_ZERO));
        assertThatThrownBy(() -> adapter.pushAri(credentials(), oneDay()))
                .hasMessageContaining("rate").hasMessageContaining("must be greater than 0").hasMessageContaining("2026-10-22");
        channex.replyAll(Reply.ok(Responses.WARNING_ROOM_NOT_FOUND));
        assertThatThrownBy(() -> adapter.pushAri(credentials(), oneDay()))
                .hasMessageContaining("Not found room_type");
    }

    @Test
    @DisplayName("경고 셋 중 셋만 싣고 나머지는 건수로 접는다")
    void 경고_요약() throws Exception {
        JsonNode warnings = json.readTree("""
                [{"warning":{"rate":["must be greater than 0"]},"date":"2026-10-22"},
                 {"warning":"Not found room_type for this change","date":"2026-10-23"},
                 {"warning":{"date":["Past date is not allowed"]},"date":"2026-09-21"},
                 {"warning":{"availability":["must be greater than or equal to 0"]},"date":"2026-10-24"}]
                """);
        assertThat(ChannexAdapter.describe(warnings))
                .contains("must be greater than 0").contains("(2026-10-22)")
                .contains("Not found room_type").contains("Past date")
                .doesNotContain("greater than or equal")
                .endsWith("외 1건");
    }

    // --- 상태 코드 세 갈래 ---------------------------------------------------------

    @Test
    @DisplayName("401 은 영구, 503 은 일시, 429 는 한도 — Retry-After 가 없으면 ratelimit 헤더의 t= 를 읽는다")
    void 상태_코드를_세_갈래로_옮긴다() {
        channex.replyAll(new Reply(401, Responses.UNAUTHORIZED, Map.of()));
        assertThatThrownBy(() -> adapter.pushAri(credentials(), oneDay()))
                .isInstanceOf(ChannelException.PermanentChannelException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("probe-key"));

        channex.replyAll(new Reply(503, "", Map.of()));
        assertThatThrownBy(() -> adapter.pushAri(credentials(), oneDay()))
                .isInstanceOf(ChannelException.TransientChannelException.class);

        channex.replyAll(new Reply(429, "{\"errors\":{\"code\":\"too_many_requests\"}}", Map.of("Retry-After", "45")));
        assertThatThrownBy(() -> adapter.pushAri(credentials(), oneDay()))
                .isInstanceOf(ChannelException.RateLimitedException.class)
                .satisfies(e -> assertThat(((ChannelException.RateLimitedException) e).retryAfter())
                        .isEqualTo(Duration.ofSeconds(45)));

        channex.replyAll(new Reply(429, "", Map.of("ratelimit", Responses.RATELIMIT_HEADER)));
        assertThatThrownBy(() -> adapter.pushAri(credentials(), oneDay()))
                .isInstanceOf(ChannelException.RateLimitedException.class)
                .satisfies(e -> assertThat(((ChannelException.RateLimitedException) e).retryAfter())
                        .isEqualTo(Duration.ofSeconds(21)));

        assertThat(ChannexAdapter.retryAfterOf(null, null)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("닿지 못하면 일시 오류이고 메시지에 주소나 키가 없다")
    void 연결_실패는_일시_오류다() {
        ChannexAdapter unreachable = new ChannexAdapter(RestClient.builder(), "http://127.0.0.1:1", 500);
        // base_url 을 안 넣은 자격 증명 — 설정의 기본 주소(여기서는 닿지 않는 곳)로 간다.
        ChannelCredentials creds = new ChannelCredentials(-1L, "BOOKING_COM", Map.of(
                ChannexAdapter.API_KEY, "probe-key", ChannexAdapter.PROPERTY_ID, PROPERTY));
        assertThatThrownBy(() -> unreachable.pushAri(creds, oneDay()))
                .isInstanceOf(ChannelException.TransientChannelException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("probe-key").doesNotContain("127.0.0.1"));
    }

    // --- 되읽기(재동기화) ----------------------------------------------------------

    @Test
    @DisplayName("요금제가 있으면 /restrictions 한 번으로 재고·요금·최소 숙박·판매중지를 읽는다")
    void 스냅샷은_restrictions_로_읽는다() {
        channex.replyAll(Reply.ok(Responses.RESTRICTIONS_SNAPSHOT));

        List<ChannelAriDay> days = adapter.fetchAriSnapshot(credentials(), ROOM, RATE,
                LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23));

        assertThat(channex.received()).hasSize(1);
        // RestClient 가 대괄호를 %5B/%5D 로 인코딩해 보낸다. 서버는 풀어 읽는다(스테이징 실측, 확인-10 2절).
        assertThat(java.net.URLDecoder.decode(channex.received().get(0).pathAndQuery(), java.nio.charset.StandardCharsets.UTF_8))
                .startsWith("/api/v1/restrictions?filter[property_id]=" + PROPERTY)
                .contains("filter[date][gte]=2026-10-22").contains("filter[date][lte]=2026-10-23");
        assertThat(days).containsExactly(
                new ChannelAriDay(LocalDate.of(2026, 10, 22), 1, new BigDecimal("120.00"), 2, false),
                // 재고 0 인 날의 stop_sell 은 Channex 가 스스로 켠 값이라 "모른다"로 — 대조가 건너뛴다.
                new ChannelAriDay(LocalDate.of(2026, 10, 23), 0, new BigDecimal("120.00"), 2, null));

        channex.reset();
        channex.replyAll(Reply.ok(Responses.AVAILABILITY_SNAPSHOT));
        assertThat(adapter.fetchAriSnapshot(credentials(), ROOM, null,
                LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23)))
                .as("요금제가 없으면 재고만 — 모르는 값은 null 이라 대조가 건너뛴다")
                .containsExactly(
                        new ChannelAriDay(LocalDate.of(2026, 10, 22), 1, null, null, null),
                        new ChannelAriDay(LocalDate.of(2026, 10, 23), 1, null, null, null));
        assertThat(channex.received().get(0).pathAndQuery()).startsWith("/api/v1/availability?");
        assertThat(java.net.URLDecoder.decode(channex.received().get(0).pathAndQuery(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("filter[property_id]=" + PROPERTY);
    }

    // --- 버퍼 → 워커 → 어댑터 (완료 조건 5·6, 9.2 B) ------------------------------------

    @Test
    @DisplayName("30일 요금 일괄 변경이 요청 한 번, 세그먼트 하나로 나간다 — 완료 조건 5")
    void 삼십일_일괄_변경은_요청_한_번이다() throws Exception {
        Fixture f = given("channex-30일");
        ChannelConnection connection = connectChannex(f);
        LocalDate from = LocalDate.of(2027, 6, 1);
        LocalDate to = from.plusDays(29);

        bulkEdit.edit(f.propertyId(), new BulkEdit.Request(
                List.of(f.unitId()), from, to, Set.of(),
                new BulkEdit.PriceChange.Fixed(BigDecimal.valueOf(130)), null, null, null, false));
        drainRelay();
        buffer.flushAll();
        worker.drainAll(5);

        List<SyncJob> jobs = worker.jobsOf(connection.getId());
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getStatus()).isEqualTo(SyncJobStatus.SUCCESS);
        List<ChannexStubServer.Received> sent = channex.received("/api/v1/restrictions");
        assertThat(sent).as("30일이 요청 하나").hasSize(1);
        JsonNode values = json.readTree(sent.get(0).body()).path("values");
        assertThat(values).as("같은 값이라 구간 하나로 압축된다").hasSize(1);
        assertThat(values.get(0).get("date_from").asText()).isEqualTo(from.toString());
        assertThat(values.get(0).get("date_to").asText()).isEqualTo(to.toString());
        assertThat(values.get(0).get("rate").asText()).isEqualTo("130.00");
        assertThat(channex.received("/api/v1/availability")).as("요금만 바꿨다").isEmpty();
    }

    @Test
    @DisplayName("초과 예약이 남은 날의 재고는 0 으로 나간다. overbooked 를 더해 보내지 않는다 — 9.2 B")
    void 초과분은_채널로_흘리지_않는다() throws Exception {
        Fixture f = given("channex-초과");
        ChannelConnection connection = connectChannex(f);
        LocalDate day = LocalDate.of(2027, 7, 1);

        // 1실짜리에 채널(다른 채널 코드) 예약 둘 → 두 번째가 forceBook, overbooked 1.
        intake.ingest(command(f, "OB-1", day));
        intake.ingest(command(f, "OB-2", day));
        assertThat(jdbc.queryForObject("SELECT overbooked_units FROM inventory_ledger WHERE unit_id = ? AND stay_date = ?",
                Integer.class, f.unitId(), day)).isEqualTo(1);
        drainRelay();
        buffer.flushAll();
        worker.drainAll(5);

        List<ChannexStubServer.Received> sent = channex.received("/api/v1/availability");
        assertThat(sent).isNotEmpty();
        JsonNode last = json.readTree(sent.get(sent.size() - 1).body()).path("values").get(0);
        assertThat(last.get("availability").asInt()).as("total 1 - booked 2 → 0. 음수도 아니고 1 도 아니다").isZero();
        assertThat(worker.jobsOf(connection.getId())).allSatisfy(job ->
                assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCESS));
    }

    @Test
    @DisplayName("판매 단위 수량을 바꾸면 오늘부터 180일의 재고가 채널로 다시 나간다 — 9.2 D")
    void 수량_변경이_채널로_나간다() throws Exception {
        Fixture f = given("channex-수량");
        ChannelConnection connection = connectChannex(f);

        capacity.change(f.unitId(), f.orgId(), (short) 3);
        drainRelay();
        buffer.flushAll();
        worker.drainAll(5);

        List<ChannexStubServer.Received> sent = channex.received("/api/v1/availability");
        assertThat(sent).as("재동기화(새벽 4시)를 기다리지 않는다").hasSize(1);
        JsonNode values = json.readTree(sent.get(0).body()).path("values");
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        assertThat(values).as("같은 값이라 구간 하나").hasSize(1);
        assertThat(values.get(0).get("availability").asInt()).isEqualTo(3);
        assertThat(values.get(0).get("date_from").asText()).isEqualTo(today.toString());
        assertThat(values.get(0).get("date_to").asText()).isEqualTo(today.plusDays(179).toString());
        assertThat(worker.jobsOf(connection.getId()).get(0).getStatus()).isEqualTo(SyncJobStatus.SUCCESS);
    }

    @Test
    @DisplayName("429 는 그 연결만 미루고 다른 연결은 그대로 나간다 — 완료 조건 6")
    void 한도는_그_연결만_멈춘다() {
        Fixture 막힘 = given("channex-429");
        Fixture 정상 = given("channex-429-이웃");
        ChannelConnection 막힌연결 = connectChannex(막힘);
        ChannelConnection 정상연결 = connectChannex(정상);
        // 어느 연결의 요청인지 본문의 room_type_id 로 가른다.
        channex.reply(r -> r.body().contains("room-" + 막힘.unitId())
                ? new Reply(429, "", Map.of("Retry-After", "60"))
                : Reply.ok(Responses.TASK_ACCEPTED));

        OffsetDateTime before = OffsetDateTime.now();
        for (Fixture f : List.of(막힘, 정상)) {
            booking.registerManual(f.propertyId(), f.unitId(), new StayPeriod(LocalDate.of(2027, 8, 1),
                    LocalDate.of(2027, 8, 2)), BigDecimal.valueOf(100), (short) 2, (short) 0, null);
        }
        drainRelay();
        buffer.flushAll();
        worker.drainAll(5);

        SyncJob 미룬것 = worker.jobsOf(막힌연결.getId()).get(0);
        assertThat(미룬것.getStatus()).isEqualTo(SyncJobStatus.PENDING);
        assertThat(미룬것.getNextRunAt()).isAfter(before.plusSeconds(50)).isBefore(before.plusSeconds(70));
        assertThat(worker.jobsOf(정상연결.getId()).get(0).getStatus())
                .as("한도는 숙소(연결)별이다. 이웃은 막히지 않는다").isEqualTo(SyncJobStatus.SUCCESS);
    }

    @Test
    @DisplayName("경고를 받은 작업은 DEAD 이고 last_error 에 경고가 남는다 — 완료 조건 7, 워커까지")
    void 경고는_워커에서_DEAD_다() {
        Fixture f = given("channex-경고");
        ChannelConnection connection = connectChannex(f);
        channex.replyAll(Reply.ok(Responses.WARNING_PAST_DATE));

        booking.registerManual(f.propertyId(), f.unitId(), new StayPeriod(LocalDate.of(2027, 9, 1),
                LocalDate.of(2027, 9, 2)), BigDecimal.valueOf(100), (short) 2, (short) 0, null);
        drainRelay();
        buffer.flushAll();
        worker.drainAll(5);

        SyncJob job = worker.jobsOf(connection.getId()).get(0);
        assertThat(job.getStatus()).as("다시 보내도 같은 답이다. 사람이 봐야 한다").isEqualTo(SyncJobStatus.DEAD);
        assertThat(job.getLastError()).contains("Past date is not allowed");
    }

    // --- 픽스처 ---------------------------------------------------------------------

    private static ChannelCredentials credentials() {
        return new ChannelCredentials(-1L, "BOOKING_COM", Map.of(
                ChannexAdapter.API_KEY, "probe-key", ChannexAdapter.PROPERTY_ID, PROPERTY,
                ChannexAdapter.BASE_URL, channex.baseUrl()));
    }

    private static AriUpdateCommand oneDay() {
        return new AriUpdateCommand(ROOM, RATE, List.of(new AriUpdateCommand.Segment(
                LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22),
                1, new BigDecimal("120"), null, null, null, null, null)));
    }

    /** 스텁을 가리키는 CHANNEX 연결. 매핑의 객실은 {@code room-<unitId>} 라 본문으로 연결을 가를 수 있다. */
    private ChannelConnection connectChannex(Fixture f) {
        ChannelConnection connection = channels.create(f.propertyId(), f.orgId(), "BOOKING_COM",
                AdapterType.CHANNEX, "Channex", Map.of(
                        ChannexAdapter.API_KEY, "stub-key", ChannexAdapter.PROPERTY_ID, PROPERTY,
                        ChannexAdapter.BASE_URL, channex.baseUrl()));
        channels.addMapping(connection.getId(), f.orgId(), f.unitId(), "room-" + f.unitId(), RATE);
        return connection;
    }

    private static ChannelBookingCommand command(Fixture f, String bookingId, LocalDate day) {
        return new ChannelBookingCommand(f.propertyId(), f.unitId(), "MOCK_OB", bookingId + "-" + f.unitId(),
                null, day, day.plusDays(1), BigDecimal.valueOf(100), 1, false);
    }
}
