package com.staysync.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.staysync.booking.BookingService;
import com.staysync.booking.ReservationDirectory;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.payment.domain.Payment;
import com.staysync.payment.domain.PaymentStatus;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 작업지시 13 의 완료 조건 9·10·11·12. 포트원 테스트 결제.
 *
 * <p><b>이 파일은 돈이 걸린 자리를 본다.</b> 아래 셋은 전부 "틀렸는데 화면은 정상으로
 * 보이는" 모양이다.
 *
 * <ul>
 *   <li>9 — 서명이 틀린 웹훅을 받아 주면 <b>아무나 예약을 확정시킨다.</b> 계획서 14.2
 *       의 검증 시나리오 10</li>
 *   <li>10 — 재조회 금액을 대조하지 않으면 <b>결제 금액과 예약 금액이 다른 예약</b>이
 *       확정된다</li>
 *   <li>11 — 웹훅 재전송은 <b>정상 동작이다.</b> 두 번 반영하면 재고를 두 번 승격해
 *       원장이 틀어지고 매출이 두 배로 잡힌다</li>
 * </ul>
 *
 * <p><b>결제사 재조회는 가짜로 바꾼다.</b> 실제 결제사에서 "금액이 어긋난 결제"를
 * 만들어 낼 수가 없다. 서명 검증은 가짜로 바꾸지 않는다 — 9번이 검증하려는 것이
 * 바로 그것이고, SDK 의 실제 검증 함수를 그대로 태운다.
 *
 * <p>포트와 데이터 디렉터리를 따로 쓴다. {@code @MockitoBean} 이 컨텍스트 캐시 키를
 * 바꾸므로 이 테스트는 별도 컨텍스트가 되고, 디렉터리가 같으면 뒤에 뜨는 쪽의
 * {@code initdb} 가 실패한다(CLAUDE.md 의 함정 목록).
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15438",
        "staysync.embedded-postgres.data-directory=.localdb-payment",
        "staysync.portone.store-id=store-test",
        "staysync.portone.channel-key=channel-test",
        "staysync.portone.api-secret=apisecret-test",
        // whsec_ 접두어 뒤는 Base64 다. SDK 가 접두어를 떼고 디코드한다.
        "staysync.portone.webhook-secret=" + PaymentWebhookTest.WEBHOOK_SECRET
})
@ActiveProfiles("local")
class PaymentWebhookTest {

    static final String WEBHOOK_SECRET = "whsec_c3RheXN5bmMtdGVzdC13ZWJob29rLXNlY3JldC0x";

    @Autowired
    private PaymentService payments;

    @Autowired
    private BookingService booking;

    @Autowired
    private ReservationDirectory reservations;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    /** 결제사 재조회. 어긋난 금액을 실제로 만들 수 없어 여기만 가짜다. */
    @MockitoBean
    private PortOneGateway gateway;

    // --- 완료 조건 9 ---------------------------------------------------------

    @Test
    @DisplayName("서명이 틀린 웹훅은 거부하고 예약을 확정하지 않는다")
    void 서명이_틀리면_거부한다() {
        Prepared p = 결제준비("서명틀림");
        String body = 결제완료본문(p.paymentId());

        assertThatThrownBy(() -> payments.handleWebhook(
                body, "msg_1", "v1,YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=", 지금()))
                .isInstanceOf(InvalidWebhookException.class);

        // 서명이 틀렸으면 본문은 읽지도 않는다. 예약도 결제도 그대로다.
        assertThat(상태(p.reservationId())).isEqualTo("HOLD");
        assertThat(결제상태(p.paymentId())).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("서명 검증 전에는 결제사에 물어보지도 않는다")
    void 서명_검증이_먼저다() {
        Prepared p = 결제준비("검증순서");

        assertThatThrownBy(() -> payments.handleWebhook(
                결제완료본문(p.paymentId()), "msg_2", "v1,d3Jvbmc=", 지금()))
                .isInstanceOf(InvalidWebhookException.class);

        // 검증을 통과하지 못한 요청으로 바깥을 두드리면, 위조 웹훅을 흘려보내는
        // 것만으로 결제사에 부하를 줄 수 있다.
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    // --- 완료 조건 10 --------------------------------------------------------

    @Test
    @DisplayName("결제사 재조회 금액이 다르면 확정하지 않는다")
    void 금액이_다르면_확정하지_않는다() {
        Prepared p = 결제준비("금액불일치");
        // 예약은 20만원인데 결제사는 1,000원이 결제됐다고 답한다.
        given(gateway.lookup(p.paymentId()))
                .willReturn(new PortOneGateway.PaymentView(true, BigDecimal.valueOf(1000)));

        String body = 결제완료본문(p.paymentId());
        String ts = 지금();

        assertThatThrownBy(() -> payments.handleWebhook(body, "msg_3", 서명(body, "msg_3", ts), ts))
                .isInstanceOf(PaymentAmountMismatchException.class);

        // 여기서 넘어가면 결제 금액과 예약 금액이 다른 예약이 확정되고
        // **화면에는 아무 이상이 없다.**
        assertThat(상태(p.reservationId())).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("결제사가 완료로 보지 않으면 확정하지 않는다")
    void 결제사가_완료가_아니면_확정하지_않는다() {
        Prepared p = 결제준비("미완료");
        given(gateway.lookup(p.paymentId()))
                .willReturn(new PortOneGateway.PaymentView(false, BigDecimal.ZERO));

        String body = 결제완료본문(p.paymentId());
        String ts = 지금();

        assertThat(payments.handleWebhook(body, "msg_4", 서명(body, "msg_4", ts), ts)).isFalse();
        assertThat(상태(p.reservationId())).isEqualTo("HOLD");
    }

    // --- 완료 조건 12 --------------------------------------------------------

    @Test
    @DisplayName("결제 성공 웹훅으로 HOLD 가 확정되고 확정 이벤트가 나간다")
    void 결제로_확정된다() {
        Prepared p = 결제준비("정상결제");
        given(gateway.lookup(p.paymentId()))
                .willReturn(new PortOneGateway.PaymentView(true, BigDecimal.valueOf(200_000)));

        String body = 결제완료본문(p.paymentId());
        String ts = 지금();

        assertThat(payments.handleWebhook(body, "msg_5", 서명(body, "msg_5", ts), ts)).isTrue();

        assertThat(상태(p.reservationId())).isEqualTo("CONFIRMED");
        assertThat(결제상태(p.paymentId())).isEqualTo(PaymentStatus.PAID);

        // 확정이 채널로 나가야 재고가 전파된다. 나가지 않으면 채널이 옛 재고를 들고 있다.
        Long 이벤트 = jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'RESERVATION_CONFIRMED'
                """, Long.class, p.reservationId());
        assertThat(이벤트).isEqualTo(1);
    }

    // --- 완료 조건 11 --------------------------------------------------------

    @Test
    @DisplayName("같은 웹훅이 두 번 와도 예약이 하나이고 결제 행도 하나다")
    void 두_번_와도_한_번만_반영된다() {
        Prepared p = 결제준비("재전송");
        given(gateway.lookup(p.paymentId()))
                .willReturn(new PortOneGateway.PaymentView(true, BigDecimal.valueOf(200_000)));

        String body = 결제완료본문(p.paymentId());
        String ts = 지금();
        String sig = 서명(body, "msg_6", ts);

        assertThat(payments.handleWebhook(body, "msg_6", sig, ts)).isTrue();
        // 포트원 웹훅은 최소 1회 전달이라 재전송이 **정상 동작**이다. 두 번째에 예외를
        // 던지면 결제는 됐는데 응답이 실패로 남아 포트원이 계속 다시 보낸다.
        assertThat(payments.handleWebhook(body, "msg_6", sig, ts)).isFalse();

        assertThat(상태(p.reservationId())).isEqualTo("CONFIRMED");

        Long 결제행 = jdbc.queryForObject(
                "SELECT count(*) FROM payment WHERE provider_tx_id = ?", Long.class, p.paymentId());
        assertThat(결제행).as("결제 행이 둘이면 매출이 두 배로 잡힌다").isEqualTo(1);

        // 재고를 두 번 승격하면 원장이 틀어져 있지도 않은 재고를 팔게 된다.
        Long 확정재고 = jdbc.queryForObject("""
                SELECT coalesce(sum(booked_units), 0) FROM inventory_ledger WHERE unit_id = ?
                """, Long.class, p.unitId());
        assertThat(확정재고).as("2박이므로 2다. 두 번 승격하면 4가 된다").isEqualTo(2);
    }

    // --- 준비 단계 -----------------------------------------------------------

    @Test
    @DisplayName("결제 준비 응답에 비밀 값이 실리지 않는다")
    void 비밀_값은_내려가지_않는다() {
        Prepared p = 결제준비("비밀값");

        PaymentService.PaymentSetup setup = payments.prepare(p.confirmationCode());

        assertThat(setup.storeId()).isEqualTo("store-test");
        assertThat(setup.channelKey()).isEqualTo("channel-test");
        // 레코드에 자리가 없어야 실릴 수가 없다. 필드가 늘면 이 줄이 먼저 깨진다.
        assertThat(setup.toString())
                .as("API 시크릿과 웹훅 시크릿은 서버 밖으로 나가지 않는다")
                .doesNotContain("apisecret-test")
                .doesNotContain(WEBHOOK_SECRET);
        // 금액은 예약에서 온다. 화면이 보낸 값을 쓰지 않는다.
        assertThat(setup.amount()).isEqualByComparingTo("200000");
    }

    @Test
    @DisplayName("홀드가 아닌 예약에는 결제창을 열지 않는다")
    void 홀드가_아니면_준비하지_않는다() {
        Prepared p = 결제준비("이미확정");
        booking.cancel(p.reservationId());

        assertThatThrownBy(() -> payments.prepare(p.confirmationCode()))
                .isInstanceOf(NotPayableException.class);
    }

    // --- 픽스처 ---------------------------------------------------------------

    private record Prepared(Long reservationId, Long unitId, String confirmationCode,
                            String paymentId) {
    }

    /** 숙소 하나에 2박 20만원 홀드를 만들고 결제를 준비한다. */
    private Prepared 결제준비(String name) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES (?) RETURNING id",
                Long.class, name + " 조직");
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        Long unitId = unitRegistration.register(
                propertyId, name + " 객실", UnitKind.ENTIRE_PLACE, (short) 1,
                BigDecimal.valueOf(100_000));

        LocalDate 체크인 = LocalDate.now().plusDays(60);
        Reservation held = booking.hold(propertyId, unitId,
                new StayPeriod(체크인, 체크인.plusDays(2)), BigDecimal.valueOf(200_000), null);

        PaymentService.PaymentSetup setup = payments.prepare(held.getConfirmationCode());
        return new Prepared(held.getId(), unitId, held.getConfirmationCode(), setup.paymentId());
    }

    /**
     * 포트원이 보내는 결제 완료 웹훅 본문.
     *
     * <p><b>서버가 실제로 받는 형태로 적는다.</b> 판별자는 {@code type} 이고 값은
     * {@code Transaction.Paid} 다 — SDK 가 그 값으로 타입을 고른다. 픽스처를 실제와
     * 다르게 적으면 그 테스트는 아무것도 보증하지 않는다(15주차에 시각 픽스처로 같은
     * 함정에 빠졌다).
     */
    private static String 결제완료본문(String paymentId) {
        return """
                {"type":"Transaction.Paid","timestamp":"%s",\
                "data":{"paymentId":"%s","storeId":"store-test","transactionId":"tx-1"}}"""
                .formatted(Instant.now().toString(), paymentId);
    }

    /**
     * Standard Webhooks 서명. <b>검증이 아니라 발신 쪽을 흉내 내는 것이다.</b>
     *
     * <p>검증은 SDK 가 한다(그것이 9번이 보는 것이다). 여기서 만드는 것은 포트원이
     * 보냈을 서명이고, 규격대로 {@code {id}.{timestamp}.{body}} 를 HMAC-SHA256 한다.
     */
    private static String 서명(String body, String webhookId, String timestamp) {
        try {
            byte[] key = Base64.getDecoder()
                    .decode(WEBHOOK_SECRET.substring("whsec_".length()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] signed = mac.doFinal(
                    "%s.%s.%s".formatted(webhookId, timestamp, body)
                            .getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(signed);
        } catch (Exception e) {
            throw new IllegalStateException("테스트 서명을 만들지 못했다.", e);
        }
    }

    /** 규격이 타임스탬프 허용 범위를 두므로 현재 시각이어야 한다. */
    private static String 지금() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private String 상태(Long reservationId) {
        return reservations.find(reservationId).orElseThrow().status();
    }

    private PaymentStatus 결제상태(String paymentId) {
        return payments.findByPaymentId(paymentId).map(Payment::getStatus).orElseThrow();
    }
}
