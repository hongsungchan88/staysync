package com.staysync.payment;

import java.math.BigDecimal;

/**
 * 결제사에 다시 물어보는 통로.
 *
 * <p><b>웹훅 본문의 금액을 믿지 않는 이유가 여기 있다.</b> 서명이 맞으면 그 본문이
 * 포트원에서 왔다는 것까지는 보장되지만, 작업지시 13 의 4절은 그 위에 한 겹을 더
 * 요구한다 — <b>결제사 API 로 금액과 주문번호를 재조회해 대조</b>한다. 웹훅은
 * 결제가 끝났다는 <b>신호</b>로만 쓰고, 판단의 근거는 재조회한 값으로 삼는다.
 *
 * <p>인터페이스로 둔 이유는 테스트다. 완료 조건 10 이 "재조회 금액이 다르면 확정하지
 * 않는다"를 요구하는데, 실제 결제사에서 어긋난 금액을 만들어 낼 수가 없다.
 */
interface PortOneGateway {

    /**
     * 그 결제의 현재 상태와 금액.
     *
     * @param paid   결제사가 결제 완료로 보고 있는가. 아니면 확정하지 않는다
     * @param amount 결제사가 기록한 실제 결제 금액
     */
    record PaymentView(boolean paid, BigDecimal amount) {
    }

    /**
     * 결제 식별자로 재조회한다.
     *
     * @throws PaymentLookupException 결제사에 물어보지 못했을 때. <b>모르면 확정하지
     *         않는다</b> — 못 물어본 것과 금액이 맞는 것은 다르다
     */
    PaymentView lookup(String paymentId);
}
