package com.staysync.channel;

import com.staysync.channel.port.AdapterType;
import com.staysync.channel.port.Capability;
import com.staysync.channel.port.ChannelAdapter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * {@link AdapterType} 으로 {@link ChannelAdapter} 구현을 찾는 자리.
 *
 * <p><b>지금 등록되는 구현은 0개다.</b> Mock 은 11주차, Channex 는 12~13주차,
 * iCal 은 13주차다. 껍데기 구현을 미리 만들어 두지 않는다 — 할 일이 없는 어댑터가
 * 프로덕션 코드에 남으면 진짜 구현이 들어올 때 그 자리를 두고 헷갈린다.
 * 5~6주차에 Outbox 소비자를 미리 만들지 않은 것과 같은 판단이다.
 *
 * <p>그래서 <b>비어 있는 레지스트리에서 조회가 실패하는 것이 정상 동작</b>이고,
 * 그걸 테스트로 고정해 둔다.
 *
 * <p>구현이 여럿일 수 있는 자리는 {@code @Primary} 가 아니라 {@code List<T>} 주입으로
 * 받는다({@code OutboxRelay} 와 같다). 빈이 하나도 없으면 빈 리스트가 온다.
 */
@Component
public class ChannelAdapterRegistry {

    private final Map<AdapterType, ChannelAdapter> byType = new EnumMap<>(AdapterType.class);

    public ChannelAdapterRegistry(List<ChannelAdapter> adapters) {
        for (ChannelAdapter adapter : adapters) {
            ChannelAdapter previous = byType.put(adapter.type(), adapter);
            if (previous != null) {
                // 한 종류에 구현이 둘이면 어느 쪽이 쓰이는지가 빈 등록 순서에 달린다.
                // 기동을 막아 그 상태로 배포되지 않게 한다.
                throw new IllegalStateException(
                        "채널 어댑터가 종류당 하나여야 합니다. type=" + adapter.type());
            }
        }
    }

    /** 등록된 어댑터. 없으면 실패한다. */
    public ChannelAdapter get(AdapterType type) {
        ChannelAdapter adapter = byType.get(type);
        if (adapter == null) {
            throw new AdapterNotRegisteredException(type);
        }
        return adapter;
    }

    /**
     * 채널 종류가 지원하는 기능. 화면과 12주차 워커가 쓰는 조회 경로다.
     *
     * <p>구현이 아니라 {@link AdapterType} 의 선언을 돌려준다. 화면이 기능을 보여 줘야
     * 하는 시점(10주차)에 어댑터가 하나도 없기 때문이다. 어댑터 구현은
     * {@code capabilities()} 에서 같은 값을 돌려주기로 했으므로 출처는 하나로 남는다.
     */
    public Set<Capability> capabilitiesOf(AdapterType type) {
        return type.capabilities();
    }
}
