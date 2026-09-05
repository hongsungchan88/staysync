package com.staysync.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.staysync.channel.ChannelMessagingConnection;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.SyncJobStatus;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.MockOtaProcess;
import com.staysync.channel.support.SyncTestBase;
import com.staysync.messaging.domain.MessageSender;
import com.staysync.messaging.domain.MessageThread;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * <b>완료 조건 1·2·3·4·5·6.</b> 메시지 수신과 발신.
 *
 * <p>시뮬레이터를 <b>별도 프로세스로 실제로 띄운다.</b> 게스트 메시지를 만들어 낼 곳이
 * 이것뿐이고(작업지시 11 의 5절 1번), 발송이 실제로 도착했는지도 여기서만 확인된다.
 *
 * <p>P4 부터 "틀렸다"의 뜻이 바뀐다. 데이터 불일치가 아니라 <b>잘못된 메시지가
 * 게스트에게 나가는 것</b>이고, 나간 뒤에는 되돌릴 수 없다.
 */
class MessagingCoreTest extends SyncTestBase {

    private static final LocalDate 체크인 = LocalDate.now().plusDays(7);
    private static final LocalDate 체크아웃 = 체크인.plusDays(2);

    @Autowired
    private MessagePoller poller;

    @Autowired
    private MessagingService messaging;

    @Autowired
    private com.staysync.booking.ChannelBookingIntake bookingIntake;

    private MockOtaProcess simulator;

    @BeforeEach
    void 시뮬레이터를_띄운다() {
        simulator = MockOtaProcess.start("stub-key", Map.of("error-rate", "0", "seed", "20260905"));
    }

    @AfterEach
    void 시뮬레이터를_내린다() {
        if (simulator != null) {
            simulator.close();
        }
    }

    // --- 수신 -------------------------------------------------------------------

    @Test
    @DisplayName("시뮬레이터가 만든 게스트 메시지가 스레드에 나타난다")
    void 게스트_메시지가_들어온다() {
        Fixture f = given("메시지 수신");
        ChannelConnection connection = connect(f, "MOCK_MSG_IN", AdapterType.MOCK,
                simulator.baseUrl(), "room-1");
        예약을_넣는다(f, "BK-IN-1");
        simulator.emitGuestMessage("BK-IN-1", "체크인 시간을 늦출 수 있을까요?", 1);

        assertThat(poller.pollOne(연결(connection))).isEqualTo(1);

        var threads = messaging.listThreads(f.orgId());
        assertThat(threads).hasSize(1);
        MessageThread thread = threads.get(0).thread();
        assertThat(thread.getUnreadCount()).isEqualTo((short) 1);
        assertThat(threads.get(0).reservation())
                .as("예약번호로 스레드가 예약에 이어져야 한다")
                .isNotNull();

        var view = messaging.openThread(thread.getId(), f.orgId());
        assertThat(view.messages()).hasSize(1);
        assertThat(view.messages().get(0).getBody()).isEqualTo("체크인 시간을 늦출 수 있을까요?");
        assertThat(view.messages().get(0).getSender()).isEqualTo(MessageSender.GUEST);
        assertThat(view.messagingSupported()).isTrue();
    }

    @Test
    @DisplayName("같은 메시지가 두 번 와도 한 건이다")
    void 중복_메시지를_한_건으로_흡수한다() {
        // 채널의 웹훅은 최소 1회 전달이고 폴링은 같은 목록을 주기마다 다시 읽는다.
        // 중복이 정상이고 거르는 것은 우리 쪽 일이다.
        Fixture f = given("메시지 중복");
        ChannelConnection connection = connect(f, "MOCK_MSG_DUP", AdapterType.MOCK,
                simulator.baseUrl(), "room-1");
        예약을_넣는다(f, "BK-DUP-1");

        // 같은 식별자로 세 번 보낸다. 시각도 거꾸로 매겨져 있다.
        simulator.emitGuestMessage("BK-DUP-1", "몇 시에 갈까요?", 3);
        assertThat(poller.pollOne(연결(connection))).isEqualTo(1);

        // 다음 주기에도 같은 목록을 다시 읽는다.
        assertThat(poller.pollOne(연결(connection))).isZero();

        Long threadId = messaging.listThreads(f.orgId()).get(0).thread().getId();
        assertThat(messaging.openThread(threadId, f.orgId()).messages()).hasSize(1);
    }

    @Test
    @DisplayName("남의 조직 스레드를 조회하면 404 다")
    void 남의_스레드는_보이지_않는다() {
        Fixture 주인 = given("스레드 주인");
        ChannelConnection connection = connect(주인, "MOCK_MSG_OWN", AdapterType.MOCK,
                simulator.baseUrl(), "room-1");
        예약을_넣는다(주인, "BK-OWN-1");
        simulator.emitGuestMessage("BK-OWN-1", "안녕하세요", 1);
        poller.pollOne(연결(connection));
        Long threadId = messaging.listThreads(주인.orgId()).get(0).thread().getId();

        Fixture 남 = given("스레드 남");

        assertThat(messaging.listThreads(남.orgId())).isEmpty();
        assertThatThrownBy(() -> messaging.openThread(threadId, 남.orgId()))
                .isInstanceOf(ThreadNotFoundException.class);
    }

    // --- 발신 -------------------------------------------------------------------

    @Test
    @DisplayName("보낸 메시지가 sync_job 을 거쳐 시뮬레이터에 도착한다")
    void 보낸_메시지가_채널에_도착한다() {
        Fixture f = given("메시지 발신");
        ChannelConnection connection = connect(f, "MOCK_MSG_OUT", AdapterType.MOCK,
                simulator.baseUrl(), "room-1");
        예약을_넣는다(f, "BK-OUT-1");
        simulator.emitGuestMessage("BK-OUT-1", "문의합니다", 1);
        poller.pollOne(연결(connection));
        Long threadId = messaging.listThreads(f.orgId()).get(0).thread().getId();

        messaging.send(threadId, f.orgId(), "15시 이후 체크인 가능합니다.", null);

        // 채널을 직접 부르지 않는다. 작업이 만들어지고 워커가 보낸다.
        assertThat(worker.jobsOf(connection.getId()))
                .as("발송은 sync_job 을 거친다")
                .hasSize(1);
        worker.drainAll(5);
        assertThat(worker.countByStatus(connection.getId(), SyncJobStatus.SUCCESS)).isEqualTo(1);

        assertThat(simulator.sentMessages()).contains("15시 이후 체크인 가능합니다.");
        // 우리 대화에도 남는다. 채널에만 가면 호스트가 같은 말을 한 번 더 쓴다.
        assertThat(messaging.openThread(threadId, f.orgId()).messages()).hasSize(2);
    }

    @Test
    @DisplayName("발송이 실패하면 재시도되고 상한을 넘으면 DEAD 로 남는다")
    void 발송_실패가_조용히_사라지지_않는다() {
        Fixture f = given("발송 실패");
        // 채널이 없는 주소를 가리킨다. 닿지 못하는 것은 일시 오류라 재시도 대상이다.
        ChannelConnection connection = connect(f, "MOCK_MSG_FAIL", AdapterType.MOCK,
                "http://localhost:1", "room-1");
        MessageThread thread = 스레드를_직접_만든다(f, "MOCK_MSG_FAIL", "thread-fail");

        messaging.dispatch(thread, MessageSender.HOST, "도착하지 못할 메시지");

        // 상한까지 돌린다. 백오프는 여기서 재는 것이 아니므로 앞당긴다.
        for (int cycle = 0; cycle < 40; cycle++) {
            jdbc.update("UPDATE sync_job SET next_run_at = now() - interval '1 second' "
                    + "WHERE connection_id = ? AND status = 'PENDING'", connection.getId());
            if (worker.drainOnce() == 0) {
                break;
            }
        }

        assertThat(worker.countByStatus(connection.getId(), SyncJobStatus.DEAD))
                .as("상한을 넘으면 DEAD 다")
                .isEqualTo(1);
        // 행을 지우지 않는다. last_error 가 있어야 사람이 원인을 보고 다시 넣는다.
        assertThat(worker.jobsOf(connection.getId()).get(0).getLastError()).isNotBlank();
    }

    @Test
    @DisplayName("MESSAGING 을 지원하지 않는 채널에는 발송 작업이 만들어지지 않는다")
    void iCal_에는_발송하지_않는다() {
        Fixture f = given("iCal 발송 불가");
        ChannelConnection ical = channels.create(f.propertyId(), f.orgId(), "AIRBNB_ICAL_MSG",
                AdapterType.ICAL, "에어비앤비", Map.of("ical_url", "https://example.com/x.ics"));
        channels.addMapping(ical.getId(), f.orgId(), f.unitId(), "listing-1", null);
        MessageThread thread = 스레드를_직접_만든다(f, "AIRBNB_ICAL_MSG", "thread-ical");

        // 작업을 만들면 워커가 어댑터를 부르고 거기서 UnsupportedOperationException 이
        // 나 8번 재시도한 끝에 DEAD 가 된다. 보낼 수 없다는 것은 그 전에 안다.
        assertThatThrownBy(() -> messaging.dispatch(thread, MessageSender.HOST, "답장"))
                .isInstanceOf(ChannelMessagingUnsupportedException.class);

        assertThat(worker.jobsOf(ical.getId())).isEmpty();
        // 대화에도 남지 않는다. 남기면 화면이 "보냈다"고 거짓말을 한다.
        assertThat(messaging.openThread(thread.getId(), f.orgId()).messages()).isEmpty();
        assertThat(messaging.openThread(thread.getId(), f.orgId()).messagingSupported()).isFalse();
    }

    // --- 픽스처 -------------------------------------------------------------------

    private ChannelMessagingConnection 연결(ChannelConnection connection) {
        return new ChannelMessagingConnection(connection.getId(),
                connection.getPropertyId(), connection.getChannelCode());
    }

    /** 스레드를 예약에 이으려면 그 채널의 예약이 먼저 있어야 한다. */
    private void 예약을_넣는다(Fixture f, String channelBookingId) {
        bookingIntake.ingest(new com.staysync.booking.ChannelBookingCommand(
                f.propertyId(), f.unitId(), 채널코드(f), channelBookingId,
                체크인, 체크아웃, BigDecimal.valueOf(200_000), 1, false));
    }

    /** 예약 수신의 채널 코드는 연결의 코드와 같아야 스레드가 이어진다. */
    private String 채널코드(Fixture f) {
        return jdbc.queryForObject(
                "SELECT channel_code FROM channel_connection WHERE property_id = ? "
                        + "ORDER BY id DESC LIMIT 1", String.class, f.propertyId());
    }

    /**
     * 스레드를 직접 만든다.
     *
     * <p>게스트 메시지 없이 발송만 보려는 테스트가 쓴다. 수신 경로를 거치면 무엇이
     * 실패했는지가 흐려진다.
     */
    private MessageThread 스레드를_직접_만든다(Fixture f, String channelCode, String externalId) {
        Long threadId = jdbc.queryForObject("""
                INSERT INTO message_thread (property_id, channel_code, external_id)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, f.propertyId(), channelCode, externalId);
        return messaging.threadOfExternal(channelCode, externalId).orElseThrow(
                () -> new IllegalStateException("스레드를 만들지 못했다. id=" + threadId));
    }
}
