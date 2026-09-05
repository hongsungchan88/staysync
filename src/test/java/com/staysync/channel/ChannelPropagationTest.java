package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.BookingService;
import com.staysync.booking.calendar.BulkEdit;
import com.staysync.booking.calendar.BulkEditService;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.SyncTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 완료 조건 8. 계획서 6.5 송신 경로의 윗단 — Outbox 소비자가 무엇을 거르는가.
 *
 * <p>일괄 편집과 예약이 남긴 이벤트를 {@code OutboxRelay} 로 실제로 흘려보낸 뒤 작업이
 * 만들어졌는지 본다. 소비자를 직접 부르면 릴레이가 이 소비자를 실제로 태우는지가
 * 검증되지 않는다 — 9주차에 실시간 갱신이 첫 입주자였고 여기가 두 번째다.
 */
class ChannelPropagationTest extends SyncTestBase {

    private static final LocalDate 첫날 = LocalDate.of(2027, 5, 1);
    private static final LocalDate 끝날 = LocalDate.of(2027, 5, 3);

    @Autowired
    private BulkEditService bulkEdit;

    @Autowired
    private BookingService booking;

    @Test
    @DisplayName("PUSH_RATE 를 지원하지 않는 연결에는 요금 작업이 만들어지지 않는다")
    void iCal_에는_요금_작업이_생기지_않는다() {
        Fixture fixture = given("전파-기능");
        ChannelConnection ical = connect(fixture, "AIRBNB_ICAL", AdapterType.ICAL,
                "http://localhost:1", "listing-1");
        ChannelConnection mock = connect(fixture, "MOCK_X", AdapterType.MOCK,
                "http://localhost:1", "room-1");

        요금을_바꾼다(fixture);

        // 계획서 6.1 이 말하는 설계의 요점이 실제로 동작하는 자리다. iCal 은 날짜만
        // 오가므로 요금을 보낼 수 없고, 그걸 화면에만 표시하고 작업은 만들지 않는다.
        assertThat(AdapterType.ICAL.capabilities()).doesNotContain(
                com.staysync.channel.port.Capability.PUSH_RATE);
        assertThat(worker.jobsOf(ical.getId()))
                .as("iCal 연결에는 요금 전파 작업이 없어야 한다")
                .isEmpty();
        assertThat(worker.jobsOf(mock.getId())).hasSize(1);
    }

    @Test
    @DisplayName("동기화를 끈 연결에는 작업이 만들어지지 않는다")
    void 꺼진_연결에는_생기지_않는다() {
        Fixture fixture = given("전파-중지");
        ChannelConnection connection = connect(fixture, "MOCK_OFF", AdapterType.MOCK,
                "http://localhost:1", "room-1");
        channels.update(connection.getId(), fixture.orgId(), null, false, null);

        요금을_바꾼다(fixture);

        assertThat(worker.jobsOf(connection.getId())).isEmpty();
    }

    @Test
    @DisplayName("매핑되지 않은 판매 단위는 어느 채널에도 나가지 않는다")
    void 매핑이_없으면_나가지_않는다() {
        Fixture fixture = given("전파-미매핑");
        ChannelConnection connection = connectWithoutMapping(fixture, "MOCK_NM",
                AdapterType.MOCK, "http://localhost:1");

        요금을_바꾼다(fixture);

        // 10주차 화면이 매핑되지 않은 단위를 눈에 띄게 표시하는 이유가 이것이다.
        assertThat(worker.jobsOf(connection.getId())).isEmpty();
    }

    @Test
    @DisplayName("예약이 들어오면 재고가 다른 연결로 전파된다")
    void 재고가_다른_연결로_나간다() {
        Fixture fixture = given("전파-재고", (short) 2);
        ChannelConnection 판_채널 = connect(fixture, "MOCK_SELLER", AdapterType.MOCK,
                "http://localhost:1", "room-seller");
        ChannelConnection 다른_채널 = connect(fixture, "MOCK_OTHER", AdapterType.MOCK,
                "http://localhost:1", "room-other");

        // 수기 예약은 채널 코드가 DIRECT 라 두 연결 모두 "다른 채널"이다.
        booking.registerManual(fixture.propertyId(), fixture.unitId(),
                new StayPeriod(첫날, 끝날), BigDecimal.valueOf(200_000),
                (short) 2, (short) 0, null);
        relay.relayPending();
        buffer.flushAll();

        assertThat(worker.jobsOf(판_채널.getId())).hasSize(1);
        assertThat(worker.jobsOf(다른_채널.getId())).hasSize(1);
        // 2실 중 1실이 팔렸으므로 남은 수량 1이 나가야 한다. 재고가 줄었다는 사실만
        // 보내고 값을 안 보내면 채널은 여전히 2실을 판다.
        assertThat(worker.jobsOf(다른_채널.getId()).get(0).getPayload().replace(" ", ""))
                .contains("\"availability\":1");
    }

    @Test
    @DisplayName("예약을 만든 채널 자신에게는 되보내지 않는다")
    void 판_채널에는_되보내지_않는다() {
        Fixture fixture = given("전파-되울림", (short) 2);
        ChannelConnection 판_채널 = connect(fixture, "MOCK_ORIGIN", AdapterType.MOCK,
                "http://localhost:1", "room-origin");
        ChannelConnection 다른_채널 = connect(fixture, "MOCK_PEER", AdapterType.MOCK,
                "http://localhost:1", "room-peer");

        // 채널이 만든 예약인 것처럼 채널 코드를 맞춰 넣는다. 수신 경로는 13주차 브랜치의
        // BookingIngestService 가 만들지만, 걸러지는 규칙은 여기서 확인할 수 있다.
        jdbc.update("""
                INSERT INTO reservation (property_id, unit_id, channel_code, channel_booking_id,
                                         confirmation_code, status, check_in, check_out, total_amount)
                VALUES (?, ?, 'MOCK_ORIGIN', 'BK-1', 'SS-ORIGIN1', 'CONFIRMED', ?, ?, 200000)
                """, fixture.propertyId(), fixture.unitId(), 첫날, 끝날);
        outboxEvent(fixture, "MOCK_ORIGIN");

        relay.relayPending();
        buffer.flushAll();

        // 계획서 6.5 수신 경로의 5번이 "**다른** 채널로 재고 차감 전파"인 이유다.
        assertThat(worker.jobsOf(판_채널.getId()))
                .as("자기가 판 예약을 자기에게 다시 알릴 이유가 없다")
                .isEmpty();
        assertThat(worker.jobsOf(다른_채널.getId())).hasSize(1);
    }

    // --- 픽스처 ---------------------------------------------------------------

    /** 9주차 일괄 편집을 그대로 쓴다. 이벤트를 손으로 만들면 실제 페이로드와 갈라진다. */
    private void 요금을_바꾼다(Fixture fixture) {
        bulkEdit.edit(fixture.propertyId(), new BulkEdit.Request(
                List.of(fixture.unitId()), 첫날, 끝날, Set.of(),
                new BulkEdit.PriceChange.Fixed(BigDecimal.valueOf(250_000)),
                null, null, null, false));
        relay.relayPending();
        buffer.flushAll();
    }

    private void outboxEvent(Fixture fixture, String channelCode) {
        jdbc.update("""
                INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
                VALUES ('RESERVATION', 1, 'RESERVATION_CONFIRMED', ?::jsonb)
                """, """
                {"reservationId":1,"propertyId":%d,"unitId":%d,"channelCode":"%s",
                 "status":"CONFIRMED","checkIn":"%s","checkOut":"%s","totalAmount":200000}
                """.formatted(fixture.propertyId(), fixture.unitId(), channelCode, 첫날, 끝날));
    }
}
