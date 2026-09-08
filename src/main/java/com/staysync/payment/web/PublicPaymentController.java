package com.staysync.payment.web;

import com.staysync.payment.PaymentService;
import com.staysync.payment.PaymentService.PaymentSetup;
import io.portone.sdk.server.webhook.WebhookVerifier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 직접예약 결제의 공개 API. 계획서 8.7.
 *
 * <p><b>{@code /public} 아래에 있다.</b> 둘 다 로그인 없이 열린다 — 하나는 위젯
 * 손님이 부르고 하나는 포트원이 부른다. 어느 쪽도 우리 세션을 가질 수 없다.
 *
 * <h2>인증 대신 무엇이 지키는가</h2>
 *
 * <p>{@code /prepare} 는 <b>확인 코드</b>가 지킨다. 추측 불가능한 난수이고 그 예약을
 * 만든 사람만 받았다. iCal 발행 토큰과 같은 성질이다.
 *
 * <p>{@code /webhook} 은 <b>서명</b>이 지킨다. 서명이 맞지 않으면 본문을 읽지도 않는다.
 * 계획서 14.2 의 검증 시나리오 10 이 이것이다.
 *
 * <h2>본문을 문자열로 받는다</h2>
 *
 * <p>서명은 <b>바이트 그대로의 본문</b>에 대해 계산된다. 객체로 역직렬화한 뒤 다시
 * 직렬화하면 공백과 키 순서가 달라져 서명이 절대 맞지 않는다. 그래서 웹훅만
 * {@code String} 으로 받고, 검증을 통과한 뒤에 SDK 가 파싱한다.
 */
@RestController
@RequestMapping("/public/payments")
class PublicPaymentController {

    private final PaymentService service;

    PublicPaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * 결제창을 열기 전 준비.
     *
     * <p>응답에 {@code storeId} 와 {@code channelKey} 만 실린다. API 시크릿과 웹훅
     * 시크릿은 <b>서버 밖으로 나가지 않는다</b> — 그 경계는
     * {@code PortOneProperties.publicConfig()} 한 곳에 있다.
     */
    @PostMapping("/prepare")
    PaymentSetup prepare(@Valid @RequestBody PrepareRequest request) {
        return service.prepare(request.confirmationCode());
    }

    /**
     * 포트원이 부르는 웹훅. <b>확정이 일어나는 유일한 자리다.</b>
     *
     * <p>헤더 이름은 Standard Webhooks 규격이고 SDK 의 상수를 그대로 쓴다. 문자열로
     * 적어 두면 규격이 바뀔 때 조용히 어긋난다.
     *
     * <p><b>200 으로 답한다.</b> 결제 완료가 아닌 이벤트나 이미 반영한 재전송도
     * 마찬가지다 — 그건 정상이고, 실패로 답하면 포트원이 계속 다시 보낸다. 서명이
     * 틀렸거나 금액이 어긋난 경우에만 예외가 올라가 4xx 가 된다.
     */
    @PostMapping("/portone/webhook")
    ResponseEntity<Void> webhook(
            @RequestBody String body,
            @RequestHeader(WebhookVerifier.HEADER_ID) String webhookId,
            @RequestHeader(WebhookVerifier.HEADER_SIGNATURE) String signature,
            @RequestHeader(WebhookVerifier.HEADER_TIMESTAMP) String timestamp) {
        service.handleWebhook(body, webhookId, signature, timestamp);
        return ResponseEntity.ok().build();
    }

    /**
     * 그 예약이 확정됐는지 물어본다.
     *
     * <p>위젯이 결제창을 닫은 뒤 이걸 몇 번 물어본다. <b>결제창의 성공을 확정으로
     * 보여 주지 않기 위해서다</b> — 확정은 웹훅이 도착해야 일어나고 그 사이에
     * 시차가 있다.
     */
    @GetMapping("/status/{confirmationCode}")
    StatusResponse status(@PathVariable String confirmationCode) {
        return new StatusResponse(service.reservationStatus(confirmationCode));
    }

    /** 예약 상태 하나만. 금액도 게스트도 싣지 않는다. */
    record StatusResponse(String status) {
    }

    /** 위젯이 보내는 것. 확인 코드 하나가 자격이다. */
    record PrepareRequest(@NotBlank String confirmationCode) {
    }
}
