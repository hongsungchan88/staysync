package com.mockota;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * 메시지를 쌓아 둔다. 게스트가 보낸 것과 우리가 보낸 것을 나눠 담는다.
 *
 * <p><b>나누는 이유는 메아리를 만들지 않기 위해서다.</b> 한 목록에 두면 우리가 보낸
 * 메시지가 다음 수집에서 게스트 메시지로 되돌아온다. 실제 채널 중에는 그렇게 구는
 * 것도 있지만, 그건 우리가 이번 주에 검증하려는 것이 아니다.
 *
 * <p><b>중복도 순서 역전도 그대로 담는다.</b> {@code BookingStore} 와 같은 판단이다 —
 * 여기서 바로잡으면 수신 멱등성을 검증할 것이 사라진다. 막는 것은 우리 쪽 일이고,
 * 그게 실제로 막는지 보려고 나쁘게 구는 상대가 필요하다.
 *
 * <p>상태는 메모리에만 있다. 재기동하면 비고, 그래도 된다(ADR 0011).
 */
@Component
public class MessageStore {

    /** 게스트 → 우리. 폴링 조회가 읽는다. */
    private final List<MockMessage> inbound = new CopyOnWriteArrayList<>();

    /** 우리 → 게스트. 무엇을 보냈는지 테스트가 확인한다. */
    private final List<MockMessage> sent = new CopyOnWriteArrayList<>();

    public void receive(MockMessage message) {
        inbound.add(message);
    }

    public void send(MockMessage message) {
        sent.add(message);
    }

    /** 넣은 순서 그대로. 순서 역전 시나리오를 확인하려면 이 순서가 보존돼야 한다. */
    public List<MockMessage> inbound() {
        return List.copyOf(inbound);
    }

    public List<MockMessage> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        inbound.clear();
        sent.clear();
    }
}
