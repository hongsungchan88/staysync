package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.BookingService;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.SyncJob;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.SyncTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 작업지시 13 의 완료 조건 1·2·3. <b>홀드가 채널 재고에 반영된다.</b>
 *
 * <p>{@code available() = total - booked - held} 이므로 HOLD 는 채널에 나갈 재고를
 * 곧바로 줄인다. 그런데 만료({@code RESERVATION_EXPIRED})만 채널로 나가고 생성은
 * 나가지 않았다 — <b>재고가 느는 쪽만 알리고 줄어드는 쪽은 알리지 않는 비대칭</b>이다.
 *
 * <p>이 결함은 <b>지금까지 열리지 않았다.</b> HOLD 를 만드는 REST 경로가 없어
 * 부르는 것이 테스트뿐이었기 때문이다. 이번 주의 직접예약 위젯이 그 첫 경로이므로
 * 위젯보다 먼저 메운다.
 *
 * <p>3번이 이 파일의 요점이다. 한쪽만 보면 비대칭이 드러나지 않는다 — 줄어든 값과
 * 돌아온 값을 <b>한 테스트 안에서 왕복으로</b> 봐야 한다.
 */
class HoldInventoryEventTest extends SyncTestBase {

    private static final LocalDate 첫날 = LocalDate.of(2027, 6, 1);
    private static final LocalDate 끝날 = 첫날.plusDays(2);

    @Autowired
    private BookingService booking;

    // --- 완료 조건 1 ---------------------------------------------------------

    @Test
    @DisplayName("HOLD 를 만들면 재고 변경 이벤트가 나간다")
    void 홀드가_이벤트를_낸다() {
        Fixture fixture = given("홀드이벤트");

        Reservation hold = 홀드(fixture);

        assertThat(이벤트타입들(hold.getId()))
                .as("만료만 내보내면 채널이 줄어든 재고를 모른 채 옛 값을 판다")
                .contains("RESERVATION_HELD");
    }

    // --- 완료 조건 2 ---------------------------------------------------------

    @Test
    @DisplayName("HOLD 이벤트로 줄어든 재고가 채널에 전파된다")
    void 줄어든_재고가_채널로_나간다() {
        Fixture fixture = given("홀드전파", (short) 2);
        ChannelConnection 채널 = connect(fixture, "MOCK_HOLD", AdapterType.MOCK,
                "http://localhost:1", "room-hold");

        홀드(fixture);
        drainRelay();
        buffer.flushAll();

        List<SyncJob> jobs = worker.jobsOf(채널.getId());
        assertThat(jobs).hasSize(1);
        // 2실 중 1실이 점유됐으므로 남은 수량 1이 나가야 한다. 사실만 보내고 값을
        // 안 보내면 채널은 여전히 2실을 판다.
        assertThat(jobs.get(0).getPayload().replace(" ", ""))
                .contains("\"availability\":1");
    }

    // --- 완료 조건 3 ---------------------------------------------------------

    @Test
    @DisplayName("HOLD 생성과 만료가 채널 값으로 왕복한다")
    void 생성과_만료가_왕복한다() {
        Fixture fixture = given("홀드왕복", (short) 2);
        ChannelConnection 채널 = connect(fixture, "MOCK_ROUND", AdapterType.MOCK,
                "http://localhost:1", "room-round");

        Reservation hold = 홀드(fixture);
        drainRelay();
        buffer.flushAll();
        assertThat(마지막_가용수량(채널)).isEqualTo(1);

        booking.expireHold(hold.getId());
        drainRelay();
        buffer.flushAll();

        // 여기가 비대칭이 드러나는 자리다. 생성 이벤트가 없으면 채널이 받은 값은
        // 2(옛 값) → 2(만료 후)로 한 번도 줄지 않는다. 로그는 전부 정상이고,
        // 그 사이에 들어온 채널 예약이 초과 판매가 된다.
        assertThat(마지막_가용수량(채널))
                .as("만료로 재고가 돌아온 것이 채널 값에도 보여야 한다")
                .isEqualTo(2);
        assertThat(이벤트타입들(hold.getId()))
                .containsSubsequence("RESERVATION_HELD", "RESERVATION_EXPIRED");
    }

    // --- 픽스처 ---------------------------------------------------------------

    private Reservation 홀드(Fixture fixture) {
        return booking.hold(fixture.propertyId(), fixture.unitId(),
                new StayPeriod(첫날, 끝날), BigDecimal.valueOf(200_000), null);
    }

    /** 그 예약이 남긴 이벤트 종류를 기록 순서대로. */
    private List<String> 이벤트타입들(Long reservationId) {
        return jdbc.queryForList("""
                SELECT event_type FROM outbox_event
                WHERE aggregate_type = 'RESERVATION' AND aggregate_id = ?
                ORDER BY id ASC
                """, String.class, reservationId);
    }

    /** 그 연결에 마지막으로 만들어진 작업이 담은 가용 수량. */
    private int 마지막_가용수량(ChannelConnection connection) {
        List<SyncJob> jobs = worker.jobsOf(connection.getId());
        String payload = jobs.get(jobs.size() - 1).getPayload().replace(" ", "");
        int at = payload.indexOf("\"availability\":");
        assertThat(at).as("가용 수량이 실린 작업이어야 한다").isNotNegative();
        return Integer.parseInt(payload.substring(at + 15).split("[,}]")[0]);
    }
}
