package com.staysync.channel.port;

import java.util.EnumSet;
import java.util.Set;

/** 채널 어댑터의 종류. */
public enum AdapterType {

    /**
     * RFC 5545 캘린더 교환. 승인 절차 없이 쓸 수 있지만 지연이 크고 날짜만 오간다.
     *
     * <p><b>{@code PUSH_*} 가 없는 것이 정상이다.</b> iCal 로는 요금도 재고 수량도 보낼
     * 수 없다. 눈으로는 "빠뜨린 것"과 구분되지 않으므로 {@code AdapterContractTest} 가
     * 선언과 실제 동작이 맞는지 확인한다 — 선언하지 않은 기능은 호출되면 실패해야 한다.
     *
     * <p>{@link Capability#SNAPSHOT_BOOKING} 은 13주차에 더했다. iCal 에는 취소 통지가
     * 없고 발행물에서 {@code VEVENT} 가 사라지는 것이 취소라, 수신부가 "목록에 없는
     * 것은 취소"로 다뤄야 한다는 것을 여기서 알린다.
     */
    ICAL(EnumSet.of(Capability.PULL_BOOKING, Capability.SNAPSHOT_BOOKING)),

    /** 상용 화이트라벨 채널매니저 API. 요금과 제약까지 보낼 수 있다. */
    CHANNEX(EnumSet.of(Capability.PUSH_AVAILABILITY, Capability.PUSH_RATE,
            Capability.PUSH_RESTRICTION, Capability.WEBHOOK_BOOKING, Capability.PULL_BOOKING)),

    /**
     * 자체 제작 시뮬레이터. 지연과 실패를 주입해 동기화 로직을 검증한다.
     *
     * <p>{@link Capability#PULL_BOOKING} 은 P3 12주차에 더했다. 10주차에는 웹훅만
     * 적었는데, 시뮬레이터가 예약 목록 조회도 함께 내놓고 있었고 12주차의 수신이
     * 폴링으로 정해졌다(작업지시 09 의 5절 1번). 선언이 실제보다 좁으면
     * {@code ChannelBookingPoller} 가 이 채널을 건너뛴다 — 폴링이 도는데 아무 예약도
     * 들어오지 않고, 로그에도 아무것도 남지 않는다.
     *
     * <p><b>{@link Capability#WEBHOOK_BOOKING} 을 13주차에 뺐다.</b> 10주차부터
     * 적혀 있었지만 {@code parseWebhook} 을 구현한 어댑터도, 웹훅을 받는 엔드포인트도
     * 없다. 선언이 실제보다 <i>넓은</i> 쪽의 어긋남이고, 12주차의 반대 방향이다 —
     * 화면은 "예약 웹훅 지원"이라고 보여 주는데 실제로는 아무것도 받지 못한다.
     * {@code AdapterContractTest} 가 잡았다. 웹훅 수신을 만들 때 다시 넣는다.
     */
    MOCK(EnumSet.of(Capability.PUSH_AVAILABILITY, Capability.PUSH_RATE,
            Capability.PUSH_RESTRICTION, Capability.PULL_BOOKING));

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
