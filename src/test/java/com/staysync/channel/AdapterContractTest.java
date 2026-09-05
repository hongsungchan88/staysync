package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.staysync.channel.port.AdapterType;
import com.staysync.channel.port.AriUpdateCommand;
import com.staysync.channel.port.Capability;
import com.staysync.channel.port.ChannelAdapter;
import com.staysync.channel.port.ChannelCredentials;
import com.staysync.channel.support.SyncTestBase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * <b>완료 조건 17.</b> 각 어댑터의 선언과 실제 동작이 맞는지.
 *
 * <p>12주차에 {@code AdapterType.MOCK} 이 {@code PULL_BOOKING} 을 선언하지 않아
 * <b>그 채널만 조용히 건너뛰는</b> 결함이 있었다. 폴링이 기능으로 거르므로 예약이
 * 하나도 안 들어오는데 로그에도 아무것도 남지 않았다.
 *
 * <p>iCal 에서 같은 자리가 다시 온다. iCal 은 {@code PUSH_RATE} 가 <b>없는 것이
 * 정상</b>이라, 눈으로는 "없는 게 맞는 것"과 "빠뜨린 것"이 구분되지 않는다.
 *
 * <p>그래서 둘을 기계로 확인한다.
 *
 * <ol>
 *   <li>선언한 기능은 <b>실제로 구현돼 있다</b> — 부르면 {@code UnsupportedOperationException}
 *       이 나오지 않는다(채널에 닿지 못해 실패하는 것은 다른 이야기다)</li>
 *   <li>선언하지 않은 기능은 <b>부르면 실패한다</b> — 조용히 빈 값이나 성공을
 *       돌려주지 않는다. 그러면 부르는 쪽의 결함이 묻힌다</li>
 * </ol>
 */
class AdapterContractTest extends SyncTestBase {

    /** 어느 채널도 닿을 수 없는 주소. 기능 확인에는 왕복이 필요 없다. */
    private static final String UNREACHABLE = "http://127.0.0.1:1";

    @Autowired
    private List<ChannelAdapter> adapters;

    @Autowired
    private ChannelAdapterRegistry registry;

    @Test
    @DisplayName("등록된 어댑터가 둘이다. Mock 과 iCal")
    void 레지스트리에_어댑터가_둘_있다() {
        assertThat(adapters).extracting(ChannelAdapter::type)
                .containsExactlyInAnyOrder(AdapterType.MOCK, AdapterType.ICAL);
        // Channex 는 계획서 3.2 의 Could 항목이고 P3 에 없다. 없는 것이 정상이다.
        assertThatThrownBy(() -> registry.get(AdapterType.CHANNEX))
                .isInstanceOf(AdapterNotRegisteredException.class);
    }

    @Test
    @DisplayName("어댑터의 기능 선언이 AdapterType 과 한 글자도 다르지 않다")
    void 선언의_출처가_하나다() {
        // 각자 적으면 화면이 보는 값과 워커가 보는 값이 갈라진다. 갈라진 쪽은
        // 전파 작업을 만들지 말아야 할 채널에 만들거나 그 반대가 된다(10주차 결정).
        for (ChannelAdapter adapter : adapters) {
            assertThat(adapter.capabilities())
                    .as("%s 의 capabilities()", adapter.type())
                    .isEqualTo(adapter.type().capabilities());
        }
    }

    @Test
    @DisplayName("선언한 기능은 실제로 구현돼 있다")
    void 선언한_기능은_동작한다() {
        for (ChannelAdapter adapter : adapters) {
            for (Capability capability : adapter.capabilities()) {
                Throwable thrown = catchThrowable(() -> probe(adapter, capability));
                assertThat(thrown)
                        .as("%s 가 %s 를 선언했는데 구현이 없다", adapter.type(), capability)
                        .isNotInstanceOf(UnsupportedOperationException.class);
            }
        }
    }

    @Test
    @DisplayName("선언하지 않은 기능은 부르면 실패한다. 조용히 성공하지 않는다")
    void 선언하지_않은_기능은_조용히_지나가지_않는다() {
        for (ChannelAdapter adapter : adapters) {
            for (Capability capability : probeable()) {
                if (adapter.supports(capability)) {
                    continue;
                }
                assertThatThrownBy(() -> probe(adapter, capability))
                        .as("%s 는 %s 를 선언하지 않았다. 부르면 실패해야 한다",
                                adapter.type(), capability)
                        .isInstanceOf(UnsupportedOperationException.class);
            }
        }
    }

    @Test
    @DisplayName("iCal 만 스냅샷 채널이다. Mock 은 아니다")
    void 스냅샷_선언이_iCal_에만_있다() {
        // 이 선언이 "목록에 없는 예약을 취소한다"를 켠다. Mock 에 잘못 켜지면
        // 아직 목록에 안 나타난 예약이 취소되고, 빠뜨리면 iCal 의 취소가 영영
        // 반영되지 않는다. 어느 쪽이든 조용하다.
        assertThat(AdapterType.ICAL.supports(Capability.SNAPSHOT_BOOKING)).isTrue();
        assertThat(AdapterType.MOCK.supports(Capability.SNAPSHOT_BOOKING)).isFalse();
        assertThat(AdapterType.CHANNEX.supports(Capability.SNAPSHOT_BOOKING)).isFalse();
    }

    /**
     * 기능 하나에 호출 하나를 대응시킨다.
     *
     * <p>{@code PUSH_*} 셋은 모두 {@code pushAri} 하나로 나간다. 재고와 요금을 한
     * 호출에 싣는 것이 병합 버퍼의 설계라서다.
     */
    private static void probe(ChannelAdapter adapter, Capability capability) {
        ChannelCredentials credentials = new ChannelCredentials(
                -1L, "CONTRACT_PROBE",
                Map.of("api_key", "probe", "base_url", UNREACHABLE, "ical_url", UNREACHABLE));

        switch (capability) {
            case PUSH_AVAILABILITY, PUSH_RATE, PUSH_RESTRICTION -> adapter.pushAri(credentials,
                    new AriUpdateCommand("x", null, List.of(new AriUpdateCommand.Segment(
                            LocalDate.now(), LocalDate.now(), 1, null, null, null, null, null, null))));
            case PULL_BOOKING -> adapter.pullBookings(credentials, null);
            case WEBHOOK_BOOKING -> adapter.parseWebhook(credentials, "{}", Map.of());
            default -> throw new IllegalArgumentException(
                    "확인할 호출이 없는 기능이다: " + capability);
        }
    }

    /** 호출 하나가 대응되는 기능만 본다. 나머지는 아직 인터페이스에 자리가 없다. */
    private static List<Capability> probeable() {
        return List.of(Capability.PUSH_AVAILABILITY, Capability.PUSH_RATE,
                Capability.PUSH_RESTRICTION, Capability.PULL_BOOKING, Capability.WEBHOOK_BOOKING);
    }
}
