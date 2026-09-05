package com.mockota;

import com.mockota.ScenarioService.ScenarioResult;
import org.springframework.web.bind.annotation.*;

/**
 * 시나리오를 거는 손잡이. 테스트가 부른다.
 *
 * <p>채널의 표면이 아니라 시험 장치의 제어면이다. 실제 OTA 에는 "지금부터 중복을
 * 보내라"는 엔드포인트가 없다. 그래서 {@link ChaosFilter} 는 이 경로를 지나가고,
 * {@link ApiKeyFilter} 는 지나가지 않는다 — 웹훅을 쏘게 만드는 것이 이 경로라서,
 * 열어 두면 "웹훅 발신에 자격 증명이 필요하다"가 검증되지 않는다.
 */
@RestController
@RequestMapping("/api/scenarios")
class ScenarioController {

    private final ScenarioService scenarios;

    ScenarioController(ScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    /** 같은 예약 중복 전송. 멱등성 검증용. */
    @PostMapping("/duplicate")
    ScenarioResult duplicate(@RequestBody(required = false) ScenarioRequest request) {
        return scenarios.duplicate(orDefaults(request));
    }

    /** 동시 예약 다발. 중복예약 방지 검증용. */
    @PostMapping("/burst")
    ScenarioResult burst(@RequestBody(required = false) ScenarioRequest request) {
        return scenarios.burst(orDefaults(request));
    }

    /** revision 순서 역전. 버전 기반 충돌 해소 검증용. */
    @PostMapping("/revision-reorder")
    ScenarioResult revisionReorder(@RequestBody(required = false) ScenarioRequest request) {
        return scenarios.revisionReorder(orDefaults(request));
    }

    /** 재고보다 많은 예약. 초과 판매 처리 검증용. */
    @PostMapping("/overbook")
    ScenarioResult overbook(@RequestBody(required = false) ScenarioRequest request) {
        return scenarios.overbook(orDefaults(request));
    }

    /** 게스트 메시지. 같은 식별자로 count 번 보내고 시각을 거꾸로 매긴다. */
    @PostMapping("/guest-message")
    ScenarioResult guestMessage(@RequestBody(required = false) ScenarioRequest request) {
        return scenarios.guestMessage(orDefaults(request));
    }

    private static ScenarioRequest orDefaults(ScenarioRequest request) {
        return request == null ? ScenarioRequest.defaults() : request;
    }
}
