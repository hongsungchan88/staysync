package com.staysync.channel.port;

import java.util.EnumSet;
import java.util.Set;

/** 채널 어댑터의 종류. */
public enum AdapterType {

    /** RFC 5545 캘린더 교환. 승인 절차 없이 쓸 수 있지만 지연이 크고 날짜만 오간다. */
    ICAL(EnumSet.of(Capability.PULL_BOOKING)),

    /** 상용 화이트라벨 채널매니저 API. 요금과 제약까지 보낼 수 있다. */
    CHANNEX(EnumSet.of(Capability.PUSH_AVAILABILITY, Capability.PUSH_RATE,
            Capability.PUSH_RESTRICTION, Capability.WEBHOOK_BOOKING, Capability.PULL_BOOKING)),

    /** 자체 제작 시뮬레이터. 지연과 실패를 주입해 동기화 로직을 검증한다. */
    MOCK(EnumSet.of(Capability.PUSH_AVAILABILITY, Capability.PUSH_RATE,
            Capability.PUSH_RESTRICTION, Capability.WEBHOOK_BOOKING));

    private final Set<Capability> capabilities;

    AdapterType(Set<Capability> capabilities) {
        this.capabilities = Set.copyOf(capabilities);
    }

    /**
     * 이 종류의 채널이 지원하는 기능.
     *
     * <p>기능을 어댑터 구현이 아니라 종류에 선언한다. 화면이 "이 연결은 요금을 전파하지
     * 못한다"를 보여 주는 시점(P3 10주차)과 어댑터가 실제로 생기는 시점(11~13주차)이
     * 다르기 때문이다. 구현이 하나도 없는 동안에도 화면은 옳은 것을 보여 줘야 한다.
     *
     * <p><b>출처는 여기 하나다.</b> 어댑터 구현은 {@code capabilities()} 에서 이 값을
     * 그대로 돌려준다. 각자 따로 적으면 둘이 어긋나고, 어긋난 쪽은 전파 작업을 만들지
     * 말아야 할 채널에 만들거나 그 반대가 된다.
     */
    public Set<Capability> capabilities() {
        return capabilities;
    }

    public boolean supports(Capability capability) {
        return capabilities.contains(capability);
    }
}
