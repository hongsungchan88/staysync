package com.staysync.channel.web;

import com.staysync.channel.IcalExportService;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * iCal 발행. 계획서 6.2 와 9장 경로표의 {@code /public/ical/:token.ics} 다.
 *
 * <p><b>인증이 URL 하나뿐이다.</b> 에어비앤비 서버가 읽어 가므로 토큰이나 헤더를
 * 요구할 수 없고, {@code SecurityConfig} 가 이 경로를 열어 둔다. 그래서 이 응답에
 * 담기는 것을 최소로 유지한다 — 게스트 이름도, 예약 건수의 의미도, 토큰 자신도
 * 발행물에 나타나지 않는다(ADR 0007 의 선).
 *
 * <p><b>토큰이 틀리면 404 다.</b> 401 이나 403 으로 답하면 "그 토큰은 있지만 권한이
 * 없다"를 알려 주는 셈이 되어 대입으로 유효한 토큰을 좁힐 수 있다. 연결이 꺼져 있을
 * 때도 같은 404 다.
 */
@RestController
class PublicIcalController {

    private final IcalExportService export;

    PublicIcalController(IcalExportService export) {
        this.export = export;
    }

    @GetMapping(value = "/public/ical/{token}.ics", produces = "text/calendar;charset=UTF-8")
    ResponseEntity<String> calendar(@PathVariable String token) {
        return export.exportByToken(token)
                .map(body -> ResponseEntity.ok()
                        // 계획서 6.2 의 5분 캐시. 상대가 15분마다 읽어 가므로 이 정도면
                        // 우리 쪽 부하는 거의 사라지고 반영 지연은 눈에 띄지 않는다.
                        .cacheControl(CacheControl.maxAge(
                                Duration.ofSeconds(IcalExportService.CACHE_SECONDS)))
                        .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                        .body(body))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
