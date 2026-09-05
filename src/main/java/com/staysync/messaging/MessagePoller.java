package com.staysync.messaging;

import com.staysync.channel.ChannelMessageGateway;
import com.staysync.channel.ChannelMessagingConnection;
import com.staysync.channel.InboundChannelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 채널에서 게스트 메시지를 긁어 온다.
 *
 * <p><b>channel 이 아니라 messaging 에 있다.</b> 예약 폴링({@code ChannelBookingPoller})은
 * channel 에 있는데 여기는 반대인 것이 의도다 — 발송은 messaging 이 시작하므로
 * messaging → channel 의존이 이미 있고, 수신까지 channel → messaging 으로 두면 두
 * 모듈이 서로를 참조해 {@code ModularityTest} 가 깨진다. 한 방향으로 몰았다.
 *
 * <p><b>{@code sync_job} 을 만들지 않는다.</b> 폴링은 실패해도 다음 주기에 같은 목록을
 * 다시 읽으므로 재시도가 이미 들어 있다. 예약 폴링과 같은 판단이다.
 *
 * <p><b>15초 사슬 밖이다.</b> 메시지는 재고가 아니라 사람이 읽는 것이라 완료 조건 1의
 * 상한과 관계가 없다. 주기를 따로 둔다.
 */
@Component
public class MessagePoller {

    private static final Logger log = LoggerFactory.getLogger(MessagePoller.class);

    private final ChannelMessageGateway gateway;
    private final MessageIngestService intake;
    private final boolean enabled;

    MessagePoller(ChannelMessageGateway gateway, MessageIngestService intake,
                  @Value("${staysync.messaging.poll-enabled:true}") boolean enabled) {
        this.gateway = gateway;
        this.intake = intake;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${staysync.messaging.poll-interval-ms:10000}",
            initialDelayString = "${staysync.messaging.poll-interval-ms:10000}")
    public void run() {
        if (enabled) {
            pollAll();
        }
    }

    /** 메시징을 지원하는 활성 연결을 한 바퀴 돈다. 테스트가 직접 부른다. */
    public int pollAll() {
        int ingested = 0;
        for (ChannelMessagingConnection connection : gateway.messagingConnections()) {
            ingested += pollOne(connection);
        }
        return ingested;
    }

    /** 연결 하나. 실패해도 예외를 올리지 않는다 — 다음 연결이 이 주기를 잃지 않게. */
    public int pollOne(ChannelMessagingConnection connection) {
        int ingested = 0;
        for (InboundChannelMessage message : gateway.pull(connection.connectionId())) {
            try {
                if (intake.ingest(connection.propertyId(), connection.channelCode(), message)) {
                    ingested++;
                }
            } catch (RuntimeException e) {
                // 메시지 하나가 깨져도 나머지는 들어와야 한다. 통째로 버리면 게스트의
                // 문의가 사라지고, 사라진 것은 다음 주기에도 같은 이유로 사라진다.
                log.warn("메시지 하나를 넣지 못했다. connectionId={} messageId={} 사유={}",
                        connection.connectionId(), message.externalMessageId(), e.toString());
            }
        }
        return ingested;
    }
}
