package com.staysync.payment;

import io.portone.sdk.server.PortOneClient;
import io.portone.sdk.server.payment.PaidPayment;
import io.portone.sdk.server.payment.Payment;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * {@link PortOneGateway} 의 실제 구현. 포트원 V2 서버 SDK 를 쓴다.
 *
 * <p><b>결제 완료로 보는 것은 {@link PaidPayment} 하나다.</b> SDK 의 {@code Payment} 는
 * 상태별로 타입이 갈리는 봉인 인터페이스라, 준비중·실패·취소가 각각 다른 타입으로
 * 온다. 타입으로 갈리므로 상태 문자열을 비교하다 오타로 통과시키는 일이 없다.
 *
 * <p><b>금액은 {@code amount.total} 이 아니라 {@code amount.paid} 를 본다.</b> total 은
 * 청구 금액이고 paid 는 실제로 결제된 금액이다. 부분 취소가 있으면 둘이 갈리는데,
 * 우리가 대조해야 하는 것은 손님이 실제로 낸 돈이다.
 */
@Component
class PortOneApiGateway implements PortOneGateway {

    /**
     * 결제사 응답을 기다리는 한계.
     *
     * <p>웹훅 처리 안에서 부르는 호출이라 무한정 기다리면 포트원 쪽 웹훅 요청이
     * 타임아웃된다. 기다리다 실패하는 편이 낫다 — 그러면 재시도가 온다.
     */
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(10);

    private final PortOneProperties properties;

    /**
     * 결제사 클라이언트. <b>처음 쓸 때 만든다.</b>
     *
     * <p>생성자에서 만들면 포트원 설정이 없는 환경에서 기동이 막힌다. 결제는 이
     * 프로젝트에서 테스트 모드 부가 기능이라 그건 과하다({@link PortOneProperties}).
     * 대신 실제로 결제사에 물어보려는 순간 설정을 확인하고, 없으면 거기서 실패한다.
     */
    private volatile PortOneClient client;

    PortOneApiGateway(PortOneProperties properties) {
        this.properties = properties;
    }

    private PortOneClient client() {
        PortOneClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                properties.requireConfigured();
                client = new PortOneClient(
                        properties.apiSecret(), properties.apiBase(), properties.storeId());
            }
            return client;
        }
    }

    @Override
    public PaymentView lookup(String paymentId) {
        Payment payment;
        try {
            payment = client().getPayment().getPayment(paymentId)
                    .get(LOOKUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 인터럽트를 삼키지 않는다. 삼키면 이 스레드를 멈추려는 쪽이 멈추지 못한다.
            Thread.currentThread().interrupt();
            throw new PaymentLookupException(paymentId, e);
        } catch (Exception e) {
            throw new PaymentLookupException(paymentId, e);
        }

        if (payment instanceof PaidPayment paid) {
            return new PaymentView(true, BigDecimal.valueOf(paid.getAmount().getPaid()));
        }
        // 준비중·실패·취소는 전부 여기다. 금액은 볼 것이 없고, 확정하지 않는다.
        return new PaymentView(false, BigDecimal.ZERO);
    }
}
