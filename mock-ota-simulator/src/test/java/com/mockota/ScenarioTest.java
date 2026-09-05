package com.mockota;

import static org.assertj.core.api.Assertions.assertThat;

import com.mockota.ScenarioService.ScenarioResult;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;

/**
 * 완료 조건 8~11. 예약을 만들어 내는 시나리오 넷.
 *
 * <p><b>방향을 헷갈리지 말 것 — 시뮬레이터는 나쁘게 굴어야 한다.</b> 여기의 모든
 * 검증은 "막았는가"가 아니라 "제대로 나쁘게 굴었는가"를 본다. 중복을 막거나 순서를
 * 바로잡으면 12주차에 검증할 것이 사라진다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScenarioTest {

    private static final LocalDate 체크인 = LocalDate.of(2026, 12, 24);
    private static final LocalDate 체크아웃 = LocalDate.of(2026, 12, 26);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private BookingStore bookingStore;

    @BeforeEach
    void 비운다() {
        bookingStore.clear();
    }

    @Test
    @DisplayName("같은 예약을 두 번 보내면 예약번호가 같은 두 건이 나온다")
    void 중복_전송은_두_건이_된다() {
        ScenarioResult result = run("duplicate",
                new ScenarioRequest("BK-DUP", "room-1", 체크인, 체크아웃, null, null, 2));

        assertThat(result.emitted()).isEqualTo(2);

        MockBooking[] polled = poll();
        assertThat(polled).hasSize(2);
        // 여기서 한 건으로 합치면 우리 쪽의 (channel_code, channel_booking_id) 유니크
        // 제약이 일하는지 알 수 없다. 막는 것은 우리 일이고, 그걸 검증하려고 중복을 보낸다.
        assertThat(polled).extracting(MockBooking::bookingId)
                .containsExactly("BK-DUP", "BK-DUP");
    }

    @Test
    @DisplayName("동시 예약 다발이 실제로 동시에 나간다")
    void 다발은_실제로_겹친다() {
        ScenarioResult result = run("burst",
                new ScenarioRequest("BK-BURST", "room-1", 체크인, 체크아웃, null, null, 5));

        // CyclicBarrier 로 전원이 도착할 때까지 붙잡으므로 겹침이 타이밍 운이 아니라
        // 구조로 보장된다. 순차로 지나가면 락도 FOR UPDATE 도 아무 일을 하지 않아
        // 12주차 재고 방어 테스트가 통과하고도 아무것도 보증하지 못한다.
        assertThat(result.maxConcurrent())
                .as("다섯 건이 같은 순간에 겹쳐야 한다")
                .isEqualTo(5);
        assertThat(result.emitted()).isEqualTo(5);

        MockBooking[] polled = poll();
        assertThat(polled).hasSize(5);
        // 같은 객실·같은 날짜를 두고 경쟁해야 중복예약 방지를 검증할 수 있다.
        assertThat(polled).allSatisfy(booking -> {
            assertThat(booking.roomId()).isEqualTo("room-1");
            assertThat(booking.checkIn()).isEqualTo(체크인);
            assertThat(booking.checkOut()).isEqualTo(체크아웃);
        });
        assertThat(polled).extracting(MockBooking::bookingId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("revision 이 높은 것부터 낮은 것 순으로 나간다")
    void 순서가_역전된_채로_나간다() {
        ScenarioResult result = run("revision-reorder",
                new ScenarioRequest("BK-REV", "room-1", 체크인, 체크아웃, null, null, 3));

        assertThat(result.emitted()).isEqualTo(3);

        // 낮은 버전이 나중에 도착해도 무시하는지가 12주차의 검증 대상이다. 여기서
        // 순서를 바로잡아 보내면 그 코드가 한 번도 실행되지 않는다.
        assertThat(poll()).extracting(MockBooking::revision).containsExactly(3, 2, 1);
        assertThat(poll()).extracting(MockBooking::bookingId)
                .containsOnly("BK-REV");
    }

    @Test
    @DisplayName("재고보다 많은 예약을 만들어 낸다")
    void 초과_판매를_만들어_낸다() {
        // 재고가 몇인지 시뮬레이터는 모른다. 알 필요도 없다 — 채널은 우리 재고를
        // 보지 않고 판다. 그게 초과 판매가 생기는 이유다.
        ScenarioResult result = run("overbook",
                new ScenarioRequest("BK-OVER", "room-1", 체크인, 체크아웃, null, null, 4));

        assertThat(result.emitted()).isEqualTo(4);

        MockBooking[] polled = poll();
        assertThat(polled).hasSize(4);
        // 같은 객실 같은 날짜 네 건. 우리 쪽은 이걸 거절하지 않고 받아들인 뒤
        // overbooking_conflict 에 남겨야 한다(CLAUDE.md 의 못박은 규칙).
        assertThat(polled).allSatisfy(booking -> {
            assertThat(booking.roomId()).isEqualTo("room-1");
            assertThat(booking.checkIn()).isEqualTo(체크인);
        });
        assertThat(polled).extracting(MockBooking::bookingId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("본문 없이 불러도 기본값으로 동작한다")
    void 빈_본문으로도_돌아간다() {
        // 시나리오가 확인하려는 것만 적게 하려고 기본값을 둔다. 순서 역전을 보려는
        // 테스트가 요금과 인원수를 채우고 있으면 요점이 묻힌다.
        assertThat(rest.postForEntity("/api/scenarios/overbook", ApiKeys.signed(null),
                ScenarioResult.class).getBody().emitted()).isEqualTo(2);
    }

    private ScenarioResult run(String scenario, ScenarioRequest request) {
        return rest.postForEntity("/api/scenarios/" + scenario, ApiKeys.signed(request),
                ScenarioResult.class).getBody();
    }

    private MockBooking[] poll() {
        return rest.exchange("/api/bookings", HttpMethod.GET, ApiKeys.signed(null),
                MockBooking[].class).getBody();
    }
}
