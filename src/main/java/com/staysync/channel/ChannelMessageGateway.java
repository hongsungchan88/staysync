package com.staysync.channel;

import java.util.List;

/**
 * channel 모듈이 messaging 을 위해 공개하는 통로.
 *
 * <p><b>의존 방향은 messaging → channel 이다.</b> 반대 방향으로 포트를 두면 안 된다 —
 * 수신을 channel 이 messaging 에 밀어 넣고 발송을 messaging 이 channel 에 요청하면
 * 두 모듈이 서로를 참조하게 되고 {@code ModularityTest} 가 깨진다. 그래서 수신과
 * 발송을 <b>둘 다 이 인터페이스 하나로</b> 모았고, 폴링 주기를 도는 것도 messaging 이다.
 *
 * <p>{@code channel} 이 {@code booking} 에 대해 갖는 관계({@code ChannelBookingIntake})와
 * 방향이 반대인 것은 의도다. 예약은 채널이 <i>발견</i>해 booking 에 넘기지만, 메시지는
 * 사람이 <i>보내는</i> 것이 먼저이고 그 주체가 messaging 이다.
 */
public interface ChannelMessageGateway {

    /**
     * 메시지를 주고받을 수 있는 활성 연결.
     *
     * <p>{@code Capability.MESSAGING} 을 선언한 종류만 나온다. iCal 은 여기 없고
     * 그것이 정상이다 — 화면은 그 사실을 보고 입력창 대신 미지원 표시를 그린다.
     */
    List<ChannelMessagingConnection> messagingConnections();

    /** 그 연결이 메시징을 지원하는지. 화면이 입력창 여부를 정할 때 쓴다. */
    boolean supportsMessaging(Long connectionId);

    /**
     * 채널에서 메시지를 긁어 온다.
     *
     * <p>실패해도 예외를 올리지 않고 빈 목록을 돌려준다. 한 채널의 실패가 다른 채널의
     * 수집을 막으면 안 되고, 폴링은 다음 주기에 같은 목록을 다시 읽으므로 재시도가
     * 이미 들어 있다.
     */
    List<InboundChannelMessage> pull(Long connectionId);

    /**
     * 발송 작업을 만든다. <b>채널을 여기서 부르지 않는다.</b>
     *
     * <p>{@code sync_job} 을 거치므로 12주차의 재시도·백오프·{@code DEAD} 와 연결별
     * 순서 보장이 그대로 적용된다(작업지시 11 의 5절 2번). 메시지 전용 큐를 만들면
     * 그 정책이 두 벌이 되고, 갈라지는 순간의 증상은 "어떤 메시지는 재시도되고 어떤
     * 메시지는 사라진다"이다.
     *
     * @param idempotencyKey 같은 발송이 두 번 대기하지 않게 하는 키.
     *                       {@code uq_syncjob_pending} 이 최종 방어선이다
     * @return 작업을 실제로 만들었으면 true. 이미 대기 중이면 false
     */
    boolean enqueueSend(Long connectionId, OutboundChannelMessage message, String idempotencyKey);
}
