package com.staysync.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.MockOtaProcess;
import com.staysync.channel.support.SyncTestBase;
import com.staysync.messaging.domain.MessageRule;
import com.staysync.messaging.domain.MessageSender;
import com.staysync.messaging.domain.MessageTemplate;
import com.staysync.messaging.domain.MessageTrigger;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * <b>완료 조건 9·10·11·12·13.</b> 자동 발송.
 *
 * <p>10번과 11번이 이 파일의 핵심이다. 둘 다 <b>잘못된 메시지가 게스트에게 나가는</b>
 * 경우이고, 나간 뒤에는 되돌릴 수 없다. 우리 쪽 로그에는 "발송 성공"으로 남는다.
 */
class AutoMessageTest extends SyncTestBase {

    /**
     * 채널 예약의 게스트.
     *
     * <p><b>P4 15주차에 메웠다.</b> {@code channel.port.InboundBooking} 에는 게스트
     * 이름이 처음부터 있었는데 {@code ChannelBookingCommand} 경계에서 떨어지고 있었고,
     * 그래서 채널 예약은 {@code guestId} 가 비어 있었다 — {@code guestName} 을 쓰는
     * 템플릿이 채널 예약에 나가지 못했으니 <b>자동 발송의 절반이 죽어 있었다</b>
     * (작업지시 12 의 5절 1번).
     *
     * <p><b>이름을 주지 않는 채널에서는 여전히 막힌다.</b> iCal 발행물에는 이름이
     * 없고(조사-02), 그때 게스트를 만들면 "안녕하세요 님" 이 나간다. 막히는 것이
     * 옳고 아래 두 테스트가 양쪽을 본다.
     */
    private static final String 본문 =
            "{{propertyName}} 예약이 확정되었습니다. {{checkIn}} 뵙겠습니다.";

    @Autowired
    private AutoMessageService auto;

    @Autowired
    private MessagingService messaging;

    @Autowired
    private ScheduledMessageJob scheduled;

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

    @Test
    @DisplayName("예약 확정으로 메시지가 한 번 나간다")
    void 예약_확정에_안내가_나간다() {
        Setup s = 준비("자동 확정", MessageTrigger.RESERVATION_CONFIRMED);
        Long reservationId = 예약(s, "AUTO-1", LocalDate.now().plusDays(5));

        assertThat(auto.apply(MessageTrigger.RESERVATION_CONFIRMED, reservationId)).isEqualTo(1);

        var thread = messaging.threadOfReservation(reservationId).orElseThrow();
        var view = messaging.openThread(thread.getId(), s.orgId());
        assertThat(view.messages()).hasSize(1);
        assertThat(view.messages().get(0).getSender())
                .as("사람이 쓰지 않았다는 사실이 드러나야 한다")
                .isEqualTo(MessageSender.SYSTEM);
        assertThat(view.messages().get(0).getBody())
                .doesNotContain("{{")
                .contains("예약이 확정되었습니다");
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 전달돼도 한 번만 나간다")
    void 중복_전달에도_한_번만_나간다() {
        // Outbox 는 최소 1회 전달이다. 두 번 오는 것이 정상이고, 게스트에게는 한 번만
        // 가야 한다. 막는 것은 uq_dispatch_once 다.
        Setup s = 준비("자동 중복", MessageTrigger.RESERVATION_CONFIRMED);
        Long reservationId = 예약(s, "AUTO-2", LocalDate.now().plusDays(5));

        assertThat(auto.apply(MessageTrigger.RESERVATION_CONFIRMED, reservationId)).isEqualTo(1);
        assertThat(auto.apply(MessageTrigger.RESERVATION_CONFIRMED, reservationId)).isZero();
        assertThat(auto.apply(MessageTrigger.RESERVATION_CONFIRMED, reservationId)).isZero();

        var thread = messaging.threadOfReservation(reservationId).orElseThrow();
        assertThat(messaging.openThread(thread.getId(), s.orgId()).messages()).hasSize(1);
    }

    @Test
    @DisplayName("취소된 예약에는 나가지 않는다")
    void 취소된_예약에는_보내지_않는다() {
        // 체크인 하루 전 알림이 취소된 예약에 나가는 것이 이 기능의 가장 흔한 사고다.
        Setup s = 준비("자동 취소", MessageTrigger.BEFORE_CHECK_IN);
        Long reservationId = 예약(s, "AUTO-3", LocalDate.now().plusDays(1));
        취소(s, "AUTO-3");

        assertThat(auto.apply(MessageTrigger.BEFORE_CHECK_IN, reservationId)).isZero();
        // 스케줄러 경로로도 나가지 않아야 한다. 대상 조회 자체가 걸러야 맞다.
        assertThat(scheduled.runTrigger(MessageTrigger.BEFORE_CHECK_IN, LocalDate.now())).isZero();
        assertThat(messaging.threadOfReservation(reservationId)).isEmpty();
    }

    @Test
    @DisplayName("규칙을 끄면 나가지 않는다")
    void 꺼둔_규칙은_돌지_않는다() {
        Setup s = 준비("자동 끄기", MessageTrigger.RESERVATION_CONFIRMED);
        auto.changeEnabled(s.ruleId(), s.orgId(), false);
        Long reservationId = 예약(s, "AUTO-4", LocalDate.now().plusDays(5));

        assertThat(auto.apply(MessageTrigger.RESERVATION_CONFIRMED, reservationId)).isZero();
        assertThat(messaging.threadOfReservation(reservationId)).isEmpty();
    }

    @Test
    @DisplayName("시간 기반 트리거 셋이 각각 제 시점에 돈다")
    void 시간_기반_트리거가_제_날짜에_돈다() {
        LocalDate 오늘 = LocalDate.now();

        // 체크인 하루 전 — 내일 체크인하는 예약이 대상이다. 부호를 뒤집지 않으면
        // 어제 체크인한 예약을 찾아 아무도 안 나온다.
        Setup 체크인전 = 준비("체크인 전", MessageTrigger.BEFORE_CHECK_IN);
        Long r1 = 예약(체크인전, "AUTO-T1", 오늘.plusDays(1));
        assertThat(scheduled.runTrigger(MessageTrigger.BEFORE_CHECK_IN, 오늘)).isPositive();
        assertThat(messaging.threadOfReservation(r1)).isPresent();

        // 체크아웃 당일 — 오늘 체크아웃하는 예약.
        Setup 체크아웃당일 = 준비("체크아웃 당일", MessageTrigger.ON_CHECK_OUT);
        Long r2 = 예약(체크아웃당일, "AUTO-T2", 오늘.minusDays(2));
        assertThat(scheduled.runTrigger(MessageTrigger.ON_CHECK_OUT, 오늘)).isPositive();
        assertThat(messaging.threadOfReservation(r2)).isPresent();

        // 체크아웃 다음 날 — 어제 체크아웃한 예약.
        Setup 체크아웃다음 = 준비("체크아웃 다음", MessageTrigger.AFTER_CHECK_OUT);
        Long r3 = 예약(체크아웃다음, "AUTO-T3", 오늘.minusDays(3));
        assertThat(scheduled.runTrigger(MessageTrigger.AFTER_CHECK_OUT, 오늘)).isPositive();
        assertThat(messaging.threadOfReservation(r3)).isPresent();
    }

    @Test
    @DisplayName("하루가 지나 다시 돌아도 같은 예약에 두 번 나가지 않는다")
    void 스케줄러가_다시_돌아도_한_번이다() {
        // 재기동하면 같은 날짜를 다시 훑는다. 스케줄러가 "오늘 이미 돌았나"를 따로
        // 기억하지 않는 이유가 유일 제약이다.
        Setup s = 준비("스케줄러 재기동", MessageTrigger.BEFORE_CHECK_IN);
        Long reservationId = 예약(s, "AUTO-5", LocalDate.now().plusDays(1));

        assertThat(scheduled.runTrigger(MessageTrigger.BEFORE_CHECK_IN, LocalDate.now()))
                .isPositive();
        int 두번째 = scheduled.runTrigger(MessageTrigger.BEFORE_CHECK_IN, LocalDate.now());

        var thread = messaging.threadOfReservation(reservationId).orElseThrow();
        assertThat(두번째).isZero();
        assertThat(messaging.openThread(thread.getId(), s.orgId()).messages()).hasSize(1);
    }

    @Test
    @DisplayName("치환할 값이 없으면 발송도 발송 기록도 남지 않는다")
    void 치환_실패는_기록도_남기지_않는다() {
        // 기록만 남으면 그 안내는 값이 채워진 뒤에도 영영 나가지 않는다.
        // 채널 예약에는 게스트 레코드가 없다. 그대로 내보내면 "{{guestName}} 님" 이 나간다.
        Setup s = 준비("치환 실패", MessageTrigger.RESERVATION_CONFIRMED,
                "{{guestName}} 님 안내드립니다");
        Long reservationId = 예약(s, "AUTO-6", LocalDate.now().plusDays(5));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> auto.apply(MessageTrigger.RESERVATION_CONFIRMED, reservationId))
                .isInstanceOf(TemplateVariableMissingException.class);

        assertThat(발송기록(s.ruleId(), reservationId))
                .as("보내지 못했으면 기록도 남지 않아야 다음에 다시 시도한다")
                .isZero();
    }

    /** 작업지시 12 의 완료 조건 14. 5절 1번의 구멍을 메운 자리다. */
    @Test
    @DisplayName("채널이 이름을 주면 {{guestName}} 템플릿이 채널 예약에도 나간다")
    void 채널이_이름을_주면_치환된다() {
        Setup s = 준비("이름 있는 채널 예약", MessageTrigger.RESERVATION_CONFIRMED,
                "{{guestName}} 님 예약이 확정되었습니다");
        Long reservationId = 이름있는_예약(s, "AUTO-7", LocalDate.now().plusDays(5), "김손님");

        assertThat(auto.apply(MessageTrigger.RESERVATION_CONFIRMED, reservationId))
                .as("경계에서 이름이 떨어지면 여기서 치환 실패로 막힌다")
                .isEqualTo(1);

        var thread = messaging.threadOfReservation(reservationId).orElseThrow();
        assertThat(messaging.openThread(thread.getId(), s.orgId()).messages())
                .singleElement()
                .satisfies(message -> assertThat(message.getBody())
                        .isEqualTo("김손님 님 예약이 확정되었습니다"));
    }

    // --- 픽스처 -------------------------------------------------------------------

    private record Setup(Long orgId, Long propertyId, Long unitId, String channelCode,
                         Long ruleId) {
    }

    private Setup 준비(String name, MessageTrigger trigger) {
        return 준비(name, trigger, 본문);
    }

    private Setup 준비(String name, MessageTrigger trigger, String templateBody) {
        Fixture f = given(name);
        String channelCode = "MOCK_AUTO_" + Math.abs(name.hashCode());
        ChannelConnection connection = connect(f, channelCode, AdapterType.MOCK,
                simulator.baseUrl(), "room-1");
        MessageTemplate template = messaging.createTemplate(
                f.orgId(), "code-" + connection.getId(), name, templateBody);
        MessageRule rule = auto.createRule(f.orgId(), f.propertyId(), trigger, template.getId());
        return new Setup(f.orgId(), f.propertyId(), f.unitId(), channelCode, rule.getId());
    }

    /** 채널 예약 하나. 자동 발송은 예약이 있어야 대상이 된다. */
    private Long 예약(Setup s, String channelBookingId, LocalDate 체크인) {
        return bookingIntake.ingest(new com.staysync.booking.ChannelBookingCommand(
                s.propertyId(), s.unitId(), s.channelCode(), channelBookingId, null,
                체크인, 체크인.plusDays(2), BigDecimal.valueOf(200_000), 1, false))
                .reservationId();
    }

    /** 게스트 이름이 실린 채널 예약. Mock 은 이름을 준다. */
    private Long 이름있는_예약(Setup s, String channelBookingId, LocalDate 체크인, String 이름) {
        return bookingIntake.ingest(new com.staysync.booking.ChannelBookingCommand(
                s.propertyId(), s.unitId(), s.channelCode(), channelBookingId, 이름,
                체크인, 체크인.plusDays(2), BigDecimal.valueOf(200_000), 1, false))
                .reservationId();
    }

    private void 취소(Setup s, String channelBookingId) {
        bookingIntake.ingest(new com.staysync.booking.ChannelBookingCommand(
                s.propertyId(), s.unitId(), s.channelCode(), channelBookingId, null,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(3),
                BigDecimal.valueOf(200_000), 2, true));
    }

    private int 발송기록(Long ruleId, Long reservationId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM message_dispatch WHERE rule_id = ? AND reservation_id = ?",
                Integer.class, ruleId, reservationId);
    }
}
