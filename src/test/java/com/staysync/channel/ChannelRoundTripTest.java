package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.calendar.CalendarGrid;
import com.staysync.booking.calendar.CalendarService;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.MockOtaProcess;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>P3 완료 조건 1.</b> 계획서 12.2 의 문장 그대로다.
 *
 * <blockquote>Mock 채널에서 발생한 예약이 15초 이내에 캘린더에 반영되고 다른 채널의
 * 재고가 자동으로 차감된다.</blockquote>
 *
 * <p><b>주기 실행을 실제 값으로 켠다.</b> 다른 테스트는 스케줄러를 멈추고
 * {@code pollAll()}/{@code flushAll()}/{@code drainOnce()} 를 직접 부르지만, 여기서
 * 재려는 것이 바로 그 주기들의 합이다. 직접 부르면 15초를 지키는지가 아니라 코드가
 * 도는지만 확인하게 된다.
 *
 * <p>경로는 넷을 지난다. 폴링 5초 + Outbox 릴레이 1초 + 병합 버퍼 6초 + 워커 1초라
 * 최악의 경우 13초다. <b>여유가 2초뿐이고 그 값이 13주차 설계의 입력이다</b>
 * (작업지시 09 의 7절).
 *
 * <p>시뮬레이터 하나에 Mock 연결 둘을 붙인다. 연결 A 가 판 예약이 우리 캘린더에
 * 들어오고, 같은 판매 단위에 매핑된 연결 B 로 남은 재고가 나가야 한다.
 */
@SpringBootTest(properties = {
        // 포트도 갈라야 한다. 여기는 릴레이를 실제 1초 주기로 켜 두는 유일한
        // 컨텍스트라, 포트가 겹치면 그 릴레이가 남의 데이터베이스의 outbox 를
        // 집어 발행해 버린다(확인-05 3절 A 를 고치면서 드러났다).
        "staysync.embedded-postgres.port=15437",
        "staysync.embedded-postgres.data-directory=.localdb-e2e",
        // build.gradle 이 테스트 전체에서 멈춰 둔 것을 이 컨텍스트만 되살린다.
        // 값은 운영 기본값 그대로다 — 다르게 두면 재는 의미가 없다.
        "staysync.outbox.relay-interval-ms=1000",
        "staysync.channel.poll-interval-ms=5000",
        "staysync.channel.buffer-tick-ms=1000",
        "staysync.channel.ari-window-ms=6000",
        "staysync.channel.worker-interval-ms=1000"
})
@ActiveProfiles("local")
class ChannelRoundTripTest {

    /** 계획서 12.2 가 정한 상한. */
    private static final Duration LIMIT = Duration.ofSeconds(15);

    private static final LocalDate 체크인 = LocalDate.of(2027, 9, 10);
    private static final LocalDate 체크아웃 = LocalDate.of(2027, 9, 12);

    @Autowired
    private ChannelConnectionService channels;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private CalendarService calendar;

    @Autowired
    private JdbcTemplate jdbc;

    private MockOtaProcess simulator;

    @BeforeEach
    void 시뮬레이터를_띄운다() {
        // 악조건은 넣지 않는다. 여기서 재는 것은 정상 경로의 지연이다.
        simulator = MockOtaProcess.start("stub-key", Map.of());
    }

    @AfterEach
    void 시뮬레이터를_내린다() {
        if (simulator != null) {
            simulator.close();
        }
    }

    @Test
    @DisplayName("Mock 채널 예약이 15초 안에 캘린더에 반영되고 다른 연결로 재고가 나간다")
    void 예약이_들어오고_재고가_나간다() {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('왕복') RETURNING id", Long.class);
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, '왕복 숙소') RETURNING id",
                Long.class, orgId);
        Long unitId = unitRegistration.register(propertyId, "왕복 객실",
                UnitKind.ENTIRE_PLACE, (short) 2, BigDecimal.valueOf(100_000));

        // 시뮬레이터 하나에 연결 둘. 채널 쪽 객실 식별자가 달라야 서로의 예약을
        // 가져가지 않는다.
        ChannelConnection 판_채널 = connect(orgId, propertyId, unitId, "MOCK_A", "room-a");
        ChannelConnection 다른_채널 = connect(orgId, propertyId, unitId, "MOCK_B", "room-b");

        long 시작 = System.nanoTime();
        // 시뮬레이터가 예약을 만들어 낸다. 여기서부터가 "Mock 채널에서 발생한 예약"이다.
        시뮬레이터에_예약을_만든다("room-a");

        // (1) 캘린더에 반영된다.
        Awaitility.await("캘린더 반영")
                .atMost(LIMIT.toMillis(), TimeUnit.MILLISECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> 잔여수량(propertyId) == 1);
        Duration 반영까지 = Duration.ofNanos(System.nanoTime() - 시작);

        // (2) 다른 연결로 재고가 나간다. 판 채널에는 되보내지 않는다.
        Awaitility.await("다른 채널로 재고 전파")
                .atMost(LIMIT.toMillis(), TimeUnit.MILLISECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> simulator.receivedAri().contains("room-b"));
        Duration 전파까지 = Duration.ofNanos(System.nanoTime() - 시작);

        String ari = simulator.receivedAri();
        assertThat(ari)
                .as("자기가 판 예약을 자기에게 되보내지 않는다")
                .doesNotContain("room-a");
        assertThat(ari.replace(" ", ""))
                .as("2실 중 1실이 팔렸으므로 남은 수량 1이 나가야 한다")
                .contains("\"availability\":1");

        // 예약은 한 건이고, 다시 폴링해도 늘지 않는다(멱등성).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE unit_id = ?", Integer.class, unitId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT channel_code FROM reservation WHERE unit_id = ?", String.class, unitId))
                .isEqualTo("MOCK_A");

        // 13주차 지시서가 쓸 값이다. 상한과의 여유가 iCal 폴링 주기와 재동기화 배치
        // 설계에 그대로 들어간다.
        System.out.printf("[P3 완료 조건 1] 캘린더 반영 %dms, 다른 채널 전파 %dms (상한 %dms)%n",
                반영까지.toMillis(), 전파까지.toMillis(), LIMIT.toMillis());
        assertThat(전파까지).isLessThan(LIMIT);
        assertThat(worker.jobsOf(다른_채널.getId())).isNotEmpty();
        assertThat(worker.jobsOf(판_채널.getId())).isEmpty();
    }

    @Autowired
    private SyncJobWorker worker;

    private ChannelConnection connect(Long orgId, Long propertyId, Long unitId,
                                      String channelCode, String externalUnitId) {
        ChannelConnection connection = channels.create(propertyId, orgId, channelCode,
                AdapterType.MOCK, channelCode,
                Map.of("api_key", "stub-key", "base_url", simulator.baseUrl()));
        channels.addMapping(connection.getId(), orgId, unitId, externalUnitId, "rate-1");
        return connection;
    }

    /** 시뮬레이터의 시나리오를 그대로 쓴다. 예약을 만들어 내는 것이 그쪽 일이다. */
    private void 시뮬레이터에_예약을_만든다(String roomId) {
        String body = """
                {"booking_id":"BK-RT","room_id":"%s","check_in":"%s","check_out":"%s","count":1}
                """.formatted(roomId, 체크인, 체크아웃);
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(simulator.baseUrl() + "/api/scenarios/duplicate"))
                            .header("X-Api-Key", "stub-key")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
        } catch (Exception e) {
            throw new IllegalStateException("시뮬레이터에 예약을 만들지 못했습니다.", e);
        }
    }

    private int 잔여수량(Long propertyId) {
        CalendarGrid grid = calendar.assemble(propertyId, 체크인, 체크인);
        return grid.units().get(0).days().get(0).avail();
    }
}
