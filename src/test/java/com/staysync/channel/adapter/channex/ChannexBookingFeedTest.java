package com.staysync.channel.adapter.channex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.staysync.channel.ChannelBookingPoller;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.port.InboundBooking;
import com.staysync.channel.support.ChannexStubServer;
import com.staysync.channel.support.ChannexStubServer.Received;
import com.staysync.channel.support.ChannexStubServer.Reply;
import com.staysync.channel.support.ChannexStubServer.Responses;
import com.staysync.channel.support.SyncTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 작업지시-17 브랜치 3 — 예약 피드 + ack. 완료 조건 8(스텁 쪽)·10·11·12·13·14·16, 9.2 A·C.
 *
 * <p>폴러·수신부·어댑터는 진짜, 상대는 {@link ChannexStubServer}. 리비전 본문은
 * {@link Responses#REVISION_NEW}(스테이징 실물)를 바탕으로 식별자·날짜만 바꾼다.
 * 완료 조건 8·9·18(부킹닷컴 테스트 예약이 실제로 캘린더에 뜨는 것)은 확인-10 3절의 실측이다.
 */
class ChannexBookingFeedTest extends SyncTestBase {

    private static final String PROPERTY = "17e754e7-9aa8-456a-ad0a-94e1d54bc8f3";
    private static final String ROOM = "92f88770-4d38-4ff8-839d-542672d92c3e";
    private static final String RATE = "46b69549-6f8b-4a55-b594-979850f45379";
    private static final LocalDate 체크인 = LocalDate.of(2027, 10, 5);
    private static final LocalDate 체크아웃 = LocalDate.of(2027, 10, 7);

    private static ChannexStubServer channex;

    @Autowired
    private ChannelBookingPoller poller;

    @Autowired
    private ObjectMapper json;

    /** 이번 폴링에 줄 리비전들. 테스트마다 갈아 끼운다. */
    private final List<String> feed = new ArrayList<>();

    /**
     * 이 테스트의 연결이 쓰는 Channex 숙소 식별자. 컨텍스트를 공유하므로 앞선 테스트의
     * Channex 연결들도 같은 스텁을 두드린다 — 피드는 이 숙소를 물은 요청에만 준다.
     */
    private String property;

    @BeforeAll
    static void 스텁을_연다() {
        channex = new ChannexStubServer();
    }

    @AfterAll
    static void 스텁을_닫는다() {
        channex.close();
    }

    @BeforeEach
    void 스텁을_비운다() {
        channex.reset();
        feed.clear();
        channex.reply(r -> {
            if (r.pathAndQuery().startsWith("/api/v1/properties/")) {
                return Reply.ok("{\"data\":{\"attributes\":{\"currency\":\"USD\"}}}");
            }
            if (r.pathAndQuery().startsWith("/api/v1/booking_revisions/feed")) {
                boolean mine = property != null && r.pathAndQuery().contains(property);
                return Reply.ok("{\"data\":[" + (mine ? String.join(",", feed) : "") + "],"
                        + "\"meta\":{\"total\":" + feed.size() + ",\"limit\":100,\"page\":1,\"order_by\":\"inserted_at\",\"order_direction\":\"asc\"}}");
            }
            if (r.pathAndQuery().contains("/ack")) {
                return Reply.ok("{\"meta\":{\"message\":\"Success\"}}");
            }
            return Reply.ok(Responses.TASK_ACCEPTED);
        });
    }

    // --- 어댑터 변환 (단위) ---------------------------------------------------------

    @Test
    @DisplayName("리비전 하나가 예약 하나로 — 식별자는 booking_id, 버전은 inserted_at 의 epoch 초, 금액은 그대로")
    void 리비전을_예약으로_옮긴다() throws Exception {
        JsonNode revision = json.readTree(Responses.REVISION_NEW);
        List<InboundBooking> bookings = ChannexAdapter.toInbound(revision);

        assertThat(bookings).hasSize(1);
        InboundBooking b = bookings.get(0);
        assertThat(b.bookingId()).isEqualTo(revision.path("attributes").path("booking_id").asText());
        assertThat(b.externalUnitId()).isEqualTo(ROOM);
        assertThat(b.checkIn()).isEqualTo(LocalDate.parse(revision.path("attributes").path("arrival_date").asText()));
        assertThat(b.checkOut()).isEqualTo(LocalDate.parse(revision.path("attributes").path("departure_date").asText()));
        assertThat(b.guestName()).isNotBlank();
        assertThat(b.totalAmount()).isEqualByComparingTo(revision.path("attributes").path("amount").asText());
        assertThat(b.revision()).isEqualTo(ChannexAdapter.epochSeconds(revision.path("attributes").path("inserted_at").asText()));
        assertThat(b.revision()).isPositive();
        assertThat(b.isCancellation()).isFalse();
        assertThat(ChannexAdapter.revisionIdOf(b)).isEqualTo(revision.path("attributes").path("id").asText());
    }

    @Test
    @DisplayName("스테이징 실물(Booking CRS 리비전)도 같은 길로 — US$361.08 이 그대로, 방 하나, 성인 2")
    void 실물_리비전을_옮긴다() throws Exception {
        JsonNode revision = json.readTree(Responses.REVISION_CRS_NEW);
        InboundBooking b = ChannexAdapter.toInbound(revision).get(0);

        assertThat(b.bookingId()).isEqualTo("87358c26-ac21-4962-aca4-571807e76238");
        assertThat(b.externalUnitId()).isEqualTo(ROOM);
        assertThat(b.checkIn()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(b.checkOut()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(b.guestName()).isEqualTo("Te st");
        assertThat(b.adults()).isEqualTo(2);
        // raw_message 의 18054(최소 단위)가 아니라 속성의 "361.08" 을 읽는다.
        assertThat(b.totalAmount()).isEqualByComparingTo("361.08");
        assertThat(b.revision()).isEqualTo(ChannexAdapter.epochSeconds("2026-09-22T10:19:36.251613"));
        assertThat(b.isCancellation()).isFalse();
        assertThat(ChannexAdapter.revisionIdOf(b)).isEqualTo("22861a65-c065-450c-ac2c-7b7894dc1c5d");
        assertThat(revision.path("attributes").path("acknowledge_status").asText()).isEqualTo("pending");
    }

    @Test
    @DisplayName("금액 없는 리비전은 미상(null)이지 0 원이 아니다 — 9.2 A")
    void 금액이_없으면_미상이다() throws Exception {
        String without = Responses.REVISION_NEW.replaceAll("\"amount\":\"[0-9.]+\"", "\"amount\":null");
        InboundBooking b = ChannexAdapter.toInbound(json.readTree(without)).get(0);
        assertThat(b.totalAmount()).isNull();
    }

    @Test
    void inserted_at_은_epoch_초다() {
        assertThat(ChannexAdapter.epochSeconds("2026-09-20T15:45:30.575064")).isEqualTo(1789919130);
        assertThat(ChannexAdapter.epochSeconds("2026-09-20T15:45:31")).isEqualTo(1789919131);
        assertThat(ChannexAdapter.epochSeconds(null)).isNull();
    }

    // --- 폴러 → 수신부 → ack ---------------------------------------------------------

    @Test
    @DisplayName("새 리비전이 캘린더에 뜨고(채널 코드 BOOKING_COM, 금액 채워짐) 커밋 뒤 ack 된다 — 완료 조건 8")
    void 수신_뒤_ack_한다() {
        Fixture f = given("channex-피드");
        connectChannex(f);
        feed.add(revision("rev-1", "bk-" + f.unitId(), "new", "2026-09-22T05:00:00", 체크인, 체크아웃, "260.00", "USD", ROOM));

        int ingested = poller.pollChannex();

        assertThat(ingested).isEqualTo(1);
        Map<String, Object> row = 예약("bk-" + f.unitId());
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("channel_code")).isEqualTo("BOOKING_COM");
        assertThat((BigDecimal) row.get("total_amount")).isEqualByComparingTo("260.00");
        assertThat(가용(f, 체크인)).isZero();
        assertThat(acks()).containsExactly("/api/v1/booking_revisions/rev-1/ack");
    }

    @Test
    @DisplayName("같은 리비전을 두 번 받아도 예약은 하나이고, 두 번째도 ack 된다 — 완료 조건 10")
    void 같은_리비전은_한_번만_들어간다() {
        Fixture f = given("channex-중복");
        connectChannex(f);
        feed.add(revision("rev-d", "bk-" + f.unitId(), "new", "2026-09-22T05:00:00", 체크인, 체크아웃, "260.00", "USD", ROOM));

        poller.pollChannex();
        poller.pollChannex();   // ack 가 실패했다면 같은 리비전이 다시 온다

        assertThat(예약수("bk-" + f.unitId())).isEqualTo(1);
        assertThat(acks()).as("두 번째는 중복이지만 처리는 끝났으니 확인한다").hasSize(2);
    }

    @Test
    @DisplayName("처리가 롤백되면 ack 하지 않는다 — 완료 조건 11")
    void 롤백이면_ack_하지_않는다() {
        Fixture f = given("channex-롤백");
        connectChannex(f);
        // NUMERIC(12,2) 를 넘는 금액. 쓰기 트랜잭션이 DB 에서 거부돼 통째로 롤백된다.
        feed.add(revision("rev-x", "bk-" + f.unitId(), "new", "2026-09-22T05:00:00", 체크인, 체크아웃,
                "99999999999999.00", "USD", ROOM));

        poller.pollChannex();

        assertThat(예약수("bk-" + f.unitId())).isZero();
        assertThat(acks()).as("롤백된 것을 확인하면 그 예약은 다시 오지 않는다").isEmpty();
    }

    @Test
    @DisplayName("매핑 없는 객실의 예약은 조용히 사라지지 않는다 — 넣지 않고 ack 도 안 한다 — 완료 조건 12")
    void 매핑_없는_예약은_남는다() {
        Fixture f = given("channex-미매핑");
        connectChannex(f);
        feed.add(revision("rev-u", "bk-" + f.unitId(), "new", "2026-09-22T05:00:00", 체크인, 체크아웃, "260.00", "USD",
                "00000000-0000-0000-0000-000000000000"));

        poller.pollChannex();

        assertThat(예약수("bk-" + f.unitId())).isZero();
        // ack 가 없으니 Channex 가 30분 동안 다시 주고 메일을 보낸다. 폴러는 주기마다 경고를 남긴다.
        assertThat(acks()).isEmpty();
    }

    @Test
    @DisplayName("한 묶음에 생성 → 수정 → 취소가 뒤섞여 와도 inserted_at 순서로 적용돼 취소로 끝난다 — 완료 조건 13")
    void 한_묶음의_여러_리비전은_순서대로다() {
        Fixture f = given("channex-묶음");
        connectChannex(f);
        String bk = "bk-" + f.unitId();
        // 피드에 일부러 거꾸로 넣는다.
        feed.add(revision("rev-3", bk, "cancelled", "2026-09-22T05:02:00", 체크인, 체크아웃.plusDays(1), "390.00", "USD", ROOM));
        feed.add(revision("rev-2", bk, "modified", "2026-09-22T05:01:00", 체크인, 체크아웃.plusDays(1), "390.00", "USD", ROOM));
        feed.add(revision("rev-1", bk, "new", "2026-09-22T05:00:00", 체크인, 체크아웃, "260.00", "USD", ROOM));

        poller.pollChannex();

        Map<String, Object> row = 예약(bk);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("check_out")).hasToString(체크아웃.plusDays(1).toString());
        assertThat(가용(f, 체크인)).as("취소면 재고가 돌아온다").isEqualTo(1);
        assertThat(acks()).containsExactly(
                "/api/v1/booking_revisions/rev-1/ack", "/api/v1/booking_revisions/rev-2/ack", "/api/v1/booking_revisions/rev-3/ack");
    }

    @Test
    @DisplayName("Channex 가 만든 예약이 취소되면 그 재고를 Channex 에도 되보낸다 — Channex 는 OTA 가 아니라 거울이다")
    void 취소된_재고는_Channex_로_되돌아간다() throws Exception {
        Fixture f = given("channex-되울림");
        ChannelConnection connection = connectChannex(f);
        String bk = "bk-" + f.unitId();
        feed.add(revision("rev-1", bk, "new", "2026-09-22T05:00:00", 체크인, 체크아웃, "260.00", "USD", ROOM));
        poller.pollChannex();
        drainRelay();
        buffer.flushAll();
        worker.drainAll(5);
        channex.reset();

        feed.clear();
        feed.add(revision("rev-2", bk, "cancelled", "2026-09-22T05:10:00", 체크인, 체크아웃, "260.00", "USD", ROOM));
        poller.pollChannex();
        drainRelay();
        buffer.flushAll();
        worker.drainAll(5);

        // 12주차의 "자기에게 되보내지 않는다"는 OTA 용이다. Channex 는 생성 때만 스스로 줄이고 변경·취소에는
        // 손대지 않으므로(실측) 되보내지 않으면 취소된 날이 0 으로 남아 부킹닷컴이 계속 닫힌다.
        List<Received> sent = channex.received("/api/v1/availability");
        assertThat(sent).as("취소된 기간의 재고가 Channex 로 나간다").hasSize(1);
        JsonNode value = json.readTree(sent.get(0).body()).path("values").get(0);
        assertThat(value.get("availability").asInt()).isEqualTo(1);
        assertThat(value.get("date_from").asText()).isEqualTo(체크인.toString());
        assertThat(value.get("date_to").asText()).isEqualTo(체크아웃.minusDays(1).toString());
        assertThat(worker.jobsOf(connection.getId())).isNotEmpty();
    }

    @Test
    @DisplayName("옛 리비전이 나중에 다시 와도 되돌리지 않는다 — inserted_at 이 단조라서")
    void 옛_리비전은_무시된다() {
        Fixture f = given("channex-역전");
        connectChannex(f);
        String bk = "bk-" + f.unitId();
        feed.add(revision("rev-new", bk, "modified", "2026-09-22T05:05:00", 체크인, 체크아웃.plusDays(2), "520.00", "USD", ROOM));
        poller.pollChannex();
        feed.clear();
        feed.add(revision("rev-old", bk, "new", "2026-09-22T05:00:00", 체크인, 체크아웃, "260.00", "USD", ROOM));
        poller.pollChannex();

        assertThat(예약(bk).get("check_out")).hasToString(체크아웃.plusDays(2).toString());
        assertThat(acks()).as("무시한 것도 처리가 끝난 것이라 확인한다").hasSize(2);
    }

    @Test
    @DisplayName("통화가 숙소 통화와 다른 리비전은 넣지 않고 ack 도 안 한다 — 완료 조건 14")
    void 통화가_다르면_막는다() {
        Fixture f = given("channex-통화");
        connectChannex(f);
        feed.add(revision("rev-c", "bk-" + f.unitId(), "new", "2026-09-22T05:00:00", 체크인, 체크아웃, "260.00", "KRW", ROOM));

        poller.pollChannex();

        assertThat(예약수("bk-" + f.unitId())).isZero();
        assertThat(acks()).isEmpty();
    }

    @Test
    @DisplayName("재고를 넘는 예약은 충돌 한 행, total 그대로, overbooked 1 — 취소되면 AUTO_CLOSED 와 overbooked 0 — 9.2 C")
    void 초과_예약의_뒷정리() {
        Fixture f = given("channex-초과");
        connectChannex(f);
        String 첫째 = "bk-a-" + f.unitId();
        String 둘째 = "bk-b-" + f.unitId();
        feed.add(revision("rev-a", 첫째, "new", "2026-09-22T05:00:00", 체크인, 체크인.plusDays(1), "130.00", "USD", ROOM));
        feed.add(revision("rev-b", 둘째, "new", "2026-09-22T05:00:01", 체크인, 체크인.plusDays(1), "130.00", "USD", ROOM));
        poller.pollChannex();

        assertThat(원장(f, 체크인)).containsEntry("total_units", 1).containsEntry("booked_units", 2).containsEntry("overbooked_units", 1);
        assertThat(열린충돌(f, 체크인)).as("(판매 단위, 날짜)에 OPEN 카드 한 행").isEqualTo(1);

        feed.clear();
        feed.add(revision("rev-b2", 둘째, "cancelled", "2026-09-22T05:10:00", 체크인, 체크인.plusDays(1), "130.00", "USD", ROOM));
        poller.pollChannex();

        assertThat(원장(f, 체크인)).containsEntry("total_units", 1).containsEntry("booked_units", 1).containsEntry("overbooked_units", 0);
        assertThat(열린충돌(f, 체크인)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM overbooking_conflict WHERE unit_id = ? AND stay_date = ? "
                        + "AND status = 'RESOLVED' AND resolution = 'AUTO_CLOSED'", Long.class, f.unitId(), 체크인))
                .as("사람이 아니라 뒷정리가 닫았다").isEqualTo(1);
    }

    @Test
    @DisplayName("Channex 는 60초 사슬이다 — 5초 사슬(pollAll)은 건너뛰고, 스케줄러 진입점 runChannex 가 돈다 — 완료 조건 16")
    void 스케줄러_진입점() {
        Fixture f = given("channex-스케줄");
        connectChannex(f);
        feed.add(revision("rev-s", "bk-" + f.unitId(), "new", "2026-09-22T05:00:00", 체크인, 체크아웃, "260.00", "USD", ROOM));

        poller.pollAll();
        assertThat(channex.received("/api/v1/booking_revisions/feed")).as("5초 사슬은 Channex 를 안 돈다").isEmpty();

        poller.runChannex();
        assertThat(channex.received("/api/v1/booking_revisions/feed")).isNotEmpty();
        assertThat(예약수("bk-" + f.unitId())).isEqualTo(1);
    }

    // --- 픽스처 ---------------------------------------------------------------------

    private ChannelConnection connectChannex(Fixture f) {
        property = PROPERTY + "-" + f.unitId();
        ChannelConnection connection = channels.create(f.propertyId(), f.orgId(), "BOOKING_COM",
                AdapterType.CHANNEX, "Channex", Map.of(
                        ChannexAdapter.API_KEY, "stub-key", ChannexAdapter.PROPERTY_ID, property,
                        ChannexAdapter.BASE_URL, channex.baseUrl()));
        channels.addMapping(connection.getId(), f.orgId(), f.unitId(), ROOM, RATE);
        return connection;
    }

    /** 스테이징 실물({@link Responses#REVISION_NEW})의 모양 그대로, 값만 바꾼 리비전. */
    private static String revision(String id, String bookingId, String status, String insertedAt,
                                   LocalDate checkIn, LocalDate checkOut, String amount, String currency, String room) {
        return Responses.revision(id, bookingId, status, insertedAt, checkIn.toString(), checkOut.toString(),
                amount, currency, room, RATE);
    }

    private List<String> acks() {
        return channex.received().stream().filter(r -> r.pathAndQuery().endsWith("/ack"))
                .map(Received::pathAndQuery).toList();
    }

    private Map<String, Object> 예약(String bookingId) {
        return jdbc.queryForMap("SELECT status, channel_code, total_amount, check_in, check_out FROM reservation "
                + "WHERE channel_booking_id = ?", bookingId);
    }

    private long 예약수(String bookingId) {
        return jdbc.queryForObject("SELECT count(*) FROM reservation WHERE channel_booking_id = ?", Long.class, bookingId);
    }

    private int 가용(Fixture f, LocalDate day) {
        return inventory.availabilityByDate(f.unitId(), day, day, inventory.capacityOf(f.unitId())).get(0).available();
    }

    private Map<String, Object> 원장(Fixture f, LocalDate day) {
        return jdbc.queryForMap("SELECT total_units, booked_units, overbooked_units FROM inventory_ledger "
                + "WHERE unit_id = ? AND stay_date = ?", f.unitId(), day);
    }

    private long 열린충돌(Fixture f, LocalDate day) {
        return jdbc.queryForObject("SELECT count(*) FROM overbooking_conflict WHERE unit_id = ? AND stay_date = ? AND status = 'OPEN'",
                Long.class, f.unitId(), day);
    }
}
