package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.SyncTestBase;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 확인-05 완료 조건 6. 폴러가 고르는 조건과 실행하는 조건이 같아야 한다.
 *
 * <p>{@code CHANNEX} 는 {@link AdapterType} 에서 {@code PULL_BOOKING} 을 선언하지만
 * 어댑터 구현이 없다. 폴러가 선언만 보고 고르면 매 주기 그 연결을 집어
 * {@link AdapterNotRegisteredException} 으로 실패하고, 5분에 27줄이 쌓인다.
 *
 * <p><b>조용히 넘기는 것도 답이 아니다.</b> 그러면 그 연결이 영영 동기화되지 않는데
 * 로그에 아무것도 남지 않는다 — 12주차 {@code MOCK} 결함이 그 모양이었다. 기동당
 * 한 번은 남아야 한다.
 */
class UnregisteredAdapterPollTest extends SyncTestBase {

    @Autowired
    private ChannelConnectionRepository connections;

    @Autowired
    private ChannelMappingRepository mappings;

    @Autowired
    private ChannelCredentialStore credentials;

    @Autowired
    private com.staysync.booking.ChannelBookingIntake intake;

    private ListAppender<ILoggingEvent> appender;
    private Logger pollerLogger;

    @BeforeEach
    void 준비한다() {
        pollerLogger = (Logger) LoggerFactory.getLogger(ChannelBookingPoller.class);
        appender = new ListAppender<>();
        appender.start();
        pollerLogger.addAppender(appender);
    }

    @AfterEach
    void 정리한다() {
        pollerLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("구현이 없는 채널은 기능 선언과 무관하게 폴링 대상이 아니다")
    void 구현이_없는_채널은_수집하지_않는다() {
        // 작업지시-17 부터 세 종류가 전부 등록된다. 그래서 "구현 없는 종류"를 빈 레지스트리를
        // 든 폴러로 만든다 — 폴러가 고르는 조건은 레지스트리를 보므로 그 자리가 검증 대상이다.
        ChannelAdapterRegistry empty = new ChannelAdapterRegistry(java.util.List.of());
        assertThat(empty.isRegistered(AdapterType.MOCK)).isFalse();
        assertThat(empty.capabilitiesOf(AdapterType.MOCK))
                .as("선언은 수집을 지원한다고 말한다. 그래서 선언만 보면 걸린다")
                .contains(com.staysync.channel.port.Capability.PULL_BOOKING);
        ChannelBookingPoller poller = new ChannelBookingPoller(
                connections, mappings, empty, credentials, intake, true);

        Fixture fixture = given("미등록어댑터");
        ChannelConnection connection = connect(fixture, "BOOKING_COM", AdapterType.MOCK,
                "http://localhost:1", "room-1");

        poller.pollAll();
        poller.pollAll();
        poller.pollAll();

        // 다른 테스트가 남긴 연결도 같은 주기에 돈다. 이 연결의 것만 본다.
        String 이_연결 = "connectionId=" + connection.getId();
        assertThat(메시지들(Level.WARN))
                .filteredOn(m -> m.contains(이_연결))
                .as("집었다가 실패하면 주기마다 한 줄씩 쌓여 진짜 경고를 덮는다")
                .noneSatisfy(m -> assertThat(m).contains("채널 예약 수집에 실패했다"));
        assertThat(메시지들(Level.WARN))
                // 빈 레지스트리라 다른 테스트가 남긴 iCal 연결도 종류당 한 줄을 남긴다. MOCK 만 센다.
                .filteredOn(m -> m.contains("등록된 어댑터가 없는 채널") && m.contains("type=MOCK"))
                .as("조용히 넘기면 이 연결이 영영 동기화되지 않는 것을 아무도 모른다")
                .hasSize(1);
    }

    private List<String> 메시지들(Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
