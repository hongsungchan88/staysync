package com.staysync.payment;

import com.staysync.booking.ReservationBrief;
import com.staysync.booking.ReservationDirectory;
import com.staysync.booking.ReservationPaymentGate;
import com.staysync.payment.domain.Payment;
import com.staysync.payment.domain.PaymentStatus;
import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.Webhook;
import io.portone.sdk.server.webhook.WebhookTransactionPaid;
import io.portone.sdk.server.webhook.WebhookVerifier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직접예약 결제. 계획서 8.7 의 둘째 문단이 명세다.
 *
 * <h2>확정은 웹훅으로만 한다</h2>
 *
 * <p>결제창 호출은 브라우저가 하고, <b>브라우저가 알려 주는 성공은 근거로 쓰지
 * 않는다.</b> 결제창이 성공을 띄운 뒤 손님이 창을 닫아도, 반대로 결제가 됐는데
 * 브라우저가 죽어도 결과는 같아야 한다. 판단의 근거는 서명된 웹훅 하나다.
 *
 * <h2>두 겹으로 대조한다</h2>
 *
 * <ol>
 *   <li><b>서명 검증.</b> 포트원 V2 웹훅은 Standard Webhooks 규격이고 SDK 의
 *       {@link WebhookVerifier} 가 검증한다. HMAC 을 직접 짜지 않는다 — 재생 공격을
 *       막는 타임스탬프 허용 범위까지 규격에 들어 있고, 그 부분이 빠져도 정상 웹훅은
 *       전부 통과하므로 아무 증상이 없다</li>
 *   <li><b>결제사 재조회.</b> 서명이 맞아도 금액은 다시 물어본다. 웹훅은 "끝났다"는
 *       신호로만 쓰고 금액은 결제사가 기록한 값으로 대조한다</li>
 * </ol>
 *
 * <h2>같은 웹훅이 두 번 와도 한 번만 반영된다</h2>
 *
 * <p>포트원 웹훅은 최소 1회 전달이라 <b>재전송이 정상 동작이다.</b> Outbox 와 같은
 * 성질이고 14·15주차에 두 번 다룬 문제다. 여기서는 결제 행의 상태를 <b>먼저 조회해
 * 갈리고</b>, 경합은 {@code uq_payment_tx} 유니크 제약이 맡는다. 예외를 잡아서 넘기는
 * 방식을 쓰지 않는다 — 트랜잭션 안에서 제약 위반을 잡으면 rollback-only 로 찍혀
 * 커밋 때 통째로 터진다(CLAUDE.md 의 함정 목록).
 */
@Service
@EnableConfigurationProperties(PortOneProperties.class)
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final String PROVIDER = "PORTONE";

    private final PaymentRepository payments;
    private final PortOneGateway gateway;
    private final PortOneProperties properties;
    private final ReservationDirectory reservations;
    private final ReservationPaymentGate gate;

    PaymentService(PaymentRepository payments, PortOneGateway gateway,
                   PortOneProperties properties, ReservationDirectory reservations,
                   ReservationPaymentGate gate) {
        this.payments = payments;
        this.gateway = gateway;
        this.properties = properties;
        this.reservations = reservations;
        this.gate = gate;
    }

    /** 위젯이 결제창을 열기 위해 받는 값. <b>비밀 값이 들어가지 않는다.</b> */
    public record PaymentSetup(String paymentId, String storeId, String channelKey,
                               BigDecimal amount, String orderName) {
    }

    /**
     * 결제창을 열기 전 준비. 결제 행을 {@code PENDING} 으로 만들어 둔다.
     *
     * <p><b>미리 만드는 이유는 웹훅이 예약을 찾는 길이 이것뿐이기 때문이다.</b> 웹훅
     * 본문에는 우리 예약 식별자가 없고, 있더라도 믿지 않는다. 우리가 만든 결제
     * 식별자를 결제창에 넘기고, 웹훅이 그 식별자를 되돌려 주면 그것으로 예약에 닿는다.
     *
     * <p><b>확인 코드로 예약을 찾는다.</b> 로그인이 없는 경로라 손님이 자기 예약을
     * 가리킬 자격이 확인 코드뿐이다. 예약 식별자로 열면 1 부터 세어 남의 예약에
     * 결제를 붙일 수 있다.
     *
     * <p>금액은 <b>예약에서 가져온다.</b> 화면이 보낸 값을 쓰지 않는다 — 홀드에서 이미
     * 한 번 대조했고, 여기서 다시 화면을 믿으면 그 대조가 무의미해진다.
     */
    @Transactional
    public PaymentSetup prepare(String confirmationCode) {
        properties.requireConfigured();

        ReservationBrief reservation = reservations.findByConfirmationCode(confirmationCode)
                .orElseThrow(() -> new PaymentNotFoundException(confirmationCode));

        if (!"HOLD".equals(reservation.status())) {
            // 이미 확정됐거나 만료·취소된 예약이다. 결제창을 열 이유가 없다.
            throw new NotPayableException(reservation.status());
        }

        String paymentId = newPaymentId();
        payments.save(Payment.pending(reservation.id(), paymentId, reservation.totalAmount()));

        log.info("결제를 준비했다. reservationId={} paymentId={} 금액={}",
                reservation.id(), paymentId, reservation.totalAmount());

        return new PaymentSetup(paymentId, properties.storeId(), properties.channelKey(),
                reservation.totalAmount(), orderNameOf(reservation));
    }

    /**
     * 웹훅을 처리한다. <b>서명을 먼저 검증하고, 그 다음에야 본문을 읽는다.</b>
     *
     * <p>이 메서드가 웹훅이 들어오는 진입점이고 컨트롤러도 테스트도 <b>이것을</b>
     * 부른다. 안쪽 메서드를 따로 부르면 프록시를 거치지 않아 트랜잭션이 걸리지 않고,
     * 그러면 테스트가 통과해도 실제 경로는 다르다(CLAUDE.md 의 함정 목록).
     *
     * @return 이번 호출이 예약을 확정으로 올렸으면 {@code true}. 재전송이거나 결제
     *         완료 이벤트가 아니면 {@code false}
     */
    @Transactional
    public boolean handleWebhook(String body, String webhookId, String signature,
                                 String timestamp) {
        Webhook webhook = verify(body, webhookId, signature, timestamp);

        // 결제 완료가 아닌 이벤트도 같은 URL 로 온다(준비·실패·취소). 우리가 반응하는
        // 것은 완료 하나뿐이고, 나머지는 조용히 넘긴다 — 400 으로 답하면 포트원이
        // 정상 이벤트를 계속 재시도한다.
        if (!(webhook instanceof WebhookTransactionPaid paid)) {
            log.debug("결제 완료가 아닌 웹훅이다. 넘긴다. type={}", webhook.getClass().getSimpleName());
            return false;
        }

        String paymentId = paid.getData().getPaymentId();
        Payment payment = payments.findByProviderAndProviderTxId(PROVIDER, paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        // **재전송을 여기서 가른다.** 이미 반영한 결제면 아무 일도 하지 않는다.
        // 예외를 던지면 포트원이 실패로 보고 계속 다시 보낸다.
        if (payment.getStatus() == PaymentStatus.PAID) {
            log.info("이미 반영한 결제 웹훅이 다시 왔다. 넘긴다. paymentId={}", paymentId);
            return false;
        }

        // **서명이 맞아도 금액은 결제사에 다시 물어본다.** 웹훅은 신호일 뿐이다.
        PortOneGateway.PaymentView actual = gateway.lookup(paymentId);
        if (!actual.paid()) {
            payment.markFailed();
            log.warn("결제 완료 웹훅인데 결제사는 완료로 보지 않는다. 확정하지 않는다. paymentId={}",
                    paymentId);
            return false;
        }
        if (actual.amount().compareTo(payment.getAmount()) != 0) {
            // 넘어가면 결제 금액과 예약 금액이 다른 예약이 확정되고 화면에는 아무
            // 이상이 없다. 금액은 로그에만 남긴다 — 응답을 받는 것은 포트원이다.
            payment.markFailed();
            log.error("결제 금액이 예약 금액과 다르다. 확정하지 않는다. "
                            + "paymentId={} 예약금액={} 결제금액={}",
                    paymentId, payment.getAmount(), actual.amount());
            throw new PaymentAmountMismatchException(payment.getAmount(), actual.amount());
        }

        payment.markPaid(OffsetDateTime.now());
        boolean promoted = gate.confirmPaid(payment.getReservationId());

        log.info("결제로 예약을 확정했다. paymentId={} reservationId={} 확정={}",
                paymentId, payment.getReservationId(), promoted);
        return promoted;
    }

    /**
     * 그 예약이 확정됐는지. 위젯이 결제창을 닫은 뒤 물어본다.
     *
     * <p><b>이 경로가 필요한 이유는 확정이 웹훅으로만 일어나기 때문이다.</b> 결제창이
     * 성공을 돌려줘도 그건 브라우저가 본 것이고, 우리 예약은 포트원이 웹훅을 보내야
     * 확정된다. 둘 사이에 시차가 있으므로 화면이 "결제됐다"를 곧바로 띄우면 아직
     * 홀드인 예약을 확정으로 보여 주게 된다.
     *
     * <p>확인 코드가 자격이다. 예약 식별자로 열면 남의 예약 상태를 훑을 수 있다.
     */
    @Transactional(readOnly = true)
    public String reservationStatus(String confirmationCode) {
        return reservations.findByConfirmationCode(confirmationCode)
                .map(ReservationBrief::status)
                .orElseThrow(() -> new PaymentNotFoundException(confirmationCode));
    }

    // --- 안쪽 -----------------------------------------------------------------

    /**
     * 서명 검증. 실패하면 <b>본문을 아무것도 읽지 않고</b> 끝난다.
     *
     * <p>SDK 예외를 우리 예외로 바꾼다. 그대로 올리면 포트원 SDK 타입이 전역 예외
     * 처리기까지 올라가고, 응답 형식이 우리 것과 달라진다.
     */
    private Webhook verify(String body, String webhookId, String signature, String timestamp) {
        properties.requireWebhookSecret();
        try {
            return new WebhookVerifier(properties.webhookSecret())
                    .verify(body, webhookId, signature, timestamp);
        } catch (WebhookVerificationException e) {
            // 이유를 자세히 남기지 않는다. 위조를 시도하는 쪽에 단서가 된다.
            log.warn("웹훅 서명 검증에 실패했다. webhookId={}", webhookId);
            throw new InvalidWebhookException();
        }
    }

    /**
     * 결제 식별자. 포트원 상점 안에서 유일해야 한다.
     *
     * <p><b>확인 코드를 그대로 쓰지 않는다.</b> 결제가 실패한 뒤 손님이 다시 시도하면
     * 같은 식별자로 두 번 결제를 만들게 되고 포트원이 거절한다. 시도마다 새로 뽑으면
     * 그 문제가 없고, 예약과의 연결은 결제 행이 들고 있다.
     */
    private static String newPaymentId() {
        return "staysync-" + UUID.randomUUID();
    }

    /** 결제창에 뜨는 주문명. 게스트 이름은 넣지 않는다 — 결제사에 보낼 이유가 없다. */
    private static String orderNameOf(ReservationBrief reservation) {
        return "숙박 %s ~ %s".formatted(reservation.checkIn(), reservation.checkOut());
    }

    /** 테스트가 준비된 결제를 확인할 때 쓴다. */
    Optional<Payment> findByPaymentId(String paymentId) {
        return payments.findByProviderAndProviderTxId(PROVIDER, paymentId);
    }
}
