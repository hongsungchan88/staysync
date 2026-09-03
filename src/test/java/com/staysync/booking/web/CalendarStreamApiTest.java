package com.staysync.booking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.staysync.shared.outbox.OutboxRelay;
import com.staysync.support.ApiTestBase;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 완료 조건 12·13. 캘린더 실시간 갱신을 실제 엔드포인트로 확인한다.
 *
 * <p>13번이 이번 주에서 가장 중요하다. 구독을 {@code orgId} 로 좁히지 않으면 남의 조직
 * 예약이 화면에 뜨는데 <b>새는 쪽에서는 아무 증상이 없다.</b> 자기 화면에 남의 예약이
 * 하나 더 보이는 것을 알아채는 사람은 드물다. 조회 API 는 {@code OwnedResources} 로
 * 막았는데 실시간 경로에 구멍이 남는 모양이라, 여기서 두 조직을 실제로 띄워 놓고 본다.
 *
 * <p>{@code MockMvc} 의 비동기 응답으로 확인한다. 허브를 직접 부르면 컨트롤러가 주체에서
 * 조직을 꺼내는 그 한 줄이 검증되지 않는데, 새는 자리가 정확히 거기다.
 *
 * <p>릴레이 스케줄러를 기다리지 않고 {@link OutboxRelay#relayPending()} 을 직접 부른다.
 * 1초를 기다리면 느려지고, 무엇보다 스케줄러와 경쟁해 결과가 흔들린다.
 */
class CalendarStreamApiTest extends ApiTestBase {

    private static final LocalDate 시작 = LocalDate.of(2027, 5, 3);

    @Autowired
    private OutboxRelay relay;

    // --- 완료 조건 12 --------------------------------------------------------

    @Test
    @DisplayName("수기 예약을 만들면 열려 있는 캘린더에 알림이 간다")
    void 수기_예약이_열려_있는_화면에_반영된다() throws Exception {
        Session 세션 = 가입("stream-book@example.com");
        Fixture f = 숙소와_판매단위(세션);

        MvcResult 스트림 = 구독(세션);
        assertThat(본문(스트림)).contains("connected");

        수기예약(세션, f, 시작);
        relay.relayPending();

        // 계획서 12.2 의 "브라우저에서 수기 예약을 만들면 캘린더에 즉시 표시되고" 가
        // 여기에 걸려 있다. 화면은 이 알림을 받고 캘린더를 다시 조회한다.
        String 본문 = 본문(스트림);
        assertThat(본문).contains("RESERVATION_CONFIRMED");
        assertThat(본문).contains("\"propertyId\":" + f.propertyId());
    }

    @Test
    @DisplayName("요금 일괄 편집도 알림을 낸다")
    void 일괄_편집도_알림을_낸다() throws Exception {
        Session 세션 = 가입("stream-rate@example.com");
        Fixture f = 숙소와_판매단위(세션);
        MvcResult 스트림 = 구독(세션);

        일괄편집(세션, f, false);
        relay.relayPending();

        assertThat(본문(스트림)).contains("RATE_BULK_EDITED");
    }

    @Test
    @DisplayName("미리보기는 알림을 내지 않는다")
    void 미리보기는_알림을_내지_않는다() throws Exception {
        Session 세션 = 가입("stream-dry@example.com");
        Fixture f = 숙소와_판매단위(세션);
        MvcResult 스트림 = 구독(세션);

        일괄편집(세션, f, true);
        relay.relayPending();

        // 아무것도 쓰지 않았으니 알릴 것도 없다. 알림이 가면 열려 있는 화면들이
        // 이유 없이 캘린더를 다시 조회한다.
        assertThat(본문(스트림)).doesNotContain("RATE_BULK_EDITED");
    }

    // --- 완료 조건 13 — 이번 주에서 가장 중요하다 -------------------------------

    @Test
    @DisplayName("다른 조직의 이벤트는 구독자에게 가지 않는다")
    void 다른_조직의_이벤트는_가지_않는다() throws Exception {
        Session 나 = 가입("stream-mine@example.com");
        Session 남 = 가입("stream-other@example.com");
        Fixture 남의숙소 = 숙소와_판매단위(남);

        MvcResult 내스트림 = 구독(나);
        MvcResult 남의스트림 = 구독(남);

        수기예약(남, 남의숙소, 시작);
        relay.relayPending();

        // 남의 조직 화면에는 가야 한다. 안 가면 이 테스트가 아무것도 확인하지 못한다.
        assertThat(본문(남의스트림))
                .contains("RESERVATION_CONFIRMED")
                .contains("\"propertyId\":" + 남의숙소.propertyId());

        // 내 화면에는 오면 안 된다. 여기가 새면 남의 조직 예약이 내 캘린더에 뜬다.
        String 내본문 = 본문(내스트림);
        assertThat(내본문).doesNotContain("RESERVATION_CONFIRMED");
        assertThat(내본문).doesNotContain("\"propertyId\":" + 남의숙소.propertyId());
    }

    @Test
    @DisplayName("토큰 없이 구독하면 401 이다")
    void 토큰_없이_구독하면_401이다() throws Exception {
        mvc.perform(get("/api/calendar/stream"))
                .andExpect(status().isUnauthorized());
    }

    // --- 도우미 --------------------------------------------------------------

    /** 스트림을 연다. 비동기 응답이라 커넥션이 열린 채로 결과가 쌓인다. */
    private MvcResult 구독(Session session) throws Exception {
        return mvc.perform(get("/api/calendar/stream")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    /** 지금까지 그 구독자에게 실제로 나간 바이트. */
    private String 본문(MvcResult 스트림) throws Exception {
        return 스트림.getResponse().getContentAsString();
    }

    private Fixture 숙소와_판매단위(Session session) throws Exception {
        MvcResult 숙소 = mvc.perform(post("/api/properties")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"실시간 숙소\",\"address\":\"서울\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long propertyId = json.readTree(숙소.getResponse().getContentAsString()).get("id").asLong();

        MvcResult 단위 = mvc.perform(post("/api/properties/" + propertyId + "/units")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"실시간 객실","unitKind":"ENTIRE_PLACE",
                                 "totalUnits":1,"basePrice":90000}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = json.readTree(단위.getResponse().getContentAsString());
        return new Fixture(propertyId, body.get("id").asLong());
    }

    private void 수기예약(Session session, Fixture f, LocalDate from) throws Exception {
        mvc.perform(post("/api/reservations")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyId":%d,"unitId":%d,"checkIn":"%s","checkOut":"%s",
                                 "totalAmount":200000,"adults":2,"children":0}
                                """.formatted(f.propertyId(), f.unitId(), from, from.plusDays(2))))
                .andExpect(status().isCreated());
    }

    private void 일괄편집(Session session, Fixture f, boolean dryRun) throws Exception {
        mvc.perform(post("/api/properties/" + f.propertyId() + "/calendar/bulk-edit")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitIds":[%d],"from":"%s","to":"%s","weekdays":[],
                                 "priceMode":"FIXED","price":150000,"dryRun":%s}
                                """.formatted(f.unitId(), 시작, 시작.plusDays(9), dryRun)))
                .andExpect(status().isOk());
    }

    private record Fixture(long propertyId, long unitId) {
    }
}
