package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.staysync.channel.port.AdapterType;
import com.staysync.channel.port.AriUpdateCommand;
import com.staysync.channel.port.Capability;
import com.staysync.channel.port.ChannelAdapter;
import com.staysync.channel.port.ChannelCredentials;
import com.staysync.channel.port.SyncResult;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 완료 조건 1·2. 어댑터 레지스트리.
 *
 * <p>스프링을 띄우지 않는다. 가짜 어댑터를 빈으로 등록하면 다른 테스트가 쓰는 컨텍스트에
 * 진짜가 아닌 구현이 섞여 들어간다. 레지스트리는 생성자로 목록을 받으므로 직접 만들면 된다.
 */
class ChannelAdapterRegistryTest {

    @Test
    @DisplayName("등록된 구현이 없으면 조회가 실패한다. null 이 아니다")
    void 등록되지_않은_종류를_찾으면_실패한다() {
        // 프로덕션의 지금 상태다. Mock 은 11주차, Channex 는 12~13주차, iCal 은 13주차다.
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(List.of());

        assertThatThrownBy(() -> registry.get(AdapterType.ICAL))
                .isInstanceOf(AdapterNotRegisteredException.class)
                .hasMessageContaining("ICAL");
    }

    @Test
    void 등록하면_타입으로_찾아지고_기능이_나온다() {
        FakeAdapter fake = new FakeAdapter(AdapterType.MOCK);
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(List.of(fake));

        assertThat(registry.get(AdapterType.MOCK)).isSameAs(fake);
        assertThat(registry.get(AdapterType.MOCK).capabilities())
                .containsExactly(Capability.PUSH_AVAILABILITY);

        // 한 종류를 등록해도 나머지는 그대로 없다
        assertThatThrownBy(() -> registry.get(AdapterType.CHANNEX))
                .isInstanceOf(AdapterNotRegisteredException.class);
    }

    @Test
    @DisplayName("한 종류에 구현이 둘이면 기동을 막는다")
    void 같은_종류의_어댑터가_둘이면_실패한다() {
        // 어느 쪽이 쓰이는지가 빈 등록 순서에 달리게 된다. 그 상태로 배포되면
        // 채널 하나가 조용히 엉뚱한 구현으로 동작한다.
        assertThatThrownBy(() -> new ChannelAdapterRegistry(
                List.of(new FakeAdapter(AdapterType.MOCK), new FakeAdapter(AdapterType.MOCK))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("어댑터가 없어도 종류의 기능 선언은 조회된다")
    void 어댑터가_없어도_기능을_알_수_있다() {
        // 화면이 "요금 전파 미지원"을 보여 줘야 하는 시점(10주차)에 어댑터가 하나도 없다.
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(List.of());

        assertThat(registry.capabilitiesOf(AdapterType.ICAL))
                .as("iCal 은 날짜만 오간다. 요금을 보낼 수 없다")
                .doesNotContain(Capability.PUSH_RATE)
                .contains(Capability.PULL_BOOKING);
        assertThat(registry.capabilitiesOf(AdapterType.CHANNEX))
                .contains(Capability.PUSH_RATE);
    }

    /** 테스트 소스 안에만 둔다. 프로덕션 코드에 껍데기 구현을 만들지 않는다(5절 2번). */
    private record FakeAdapter(AdapterType type) implements ChannelAdapter {

        @Override
        public Set<Capability> capabilities() {
            return EnumSet.of(Capability.PUSH_AVAILABILITY);
        }

        @Override
        public SyncResult pushAri(ChannelCredentials credentials, AriUpdateCommand command) {
            return SyncResult.ok(0);
        }
    }
}
