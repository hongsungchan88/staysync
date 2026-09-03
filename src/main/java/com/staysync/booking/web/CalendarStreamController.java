package com.staysync.booking.web;

import com.staysync.booking.calendar.CalendarStreamHub;
import com.staysync.shared.security.AuthenticatedUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 캘린더 실시간 갱신. 계획서 8.2 의 "다른 채널의 예약이 유입되면 SSE 로 즉시 반영".
 *
 * <p><b>구독은 인증 주체의 조직으로 좁힌다.</b> 경로나 쿼리로 조직을 받지 않는다.
 * 받으면 남의 조직 식별자를 넣어 볼 수 있고, 실시간 경로는 새어도 새는 쪽에서
 * 아무 증상이 없다. 조회 API 가 {@code OwnedResources} 를 거치는 것과 같은 자리다.
 *
 * <p>숙소 단위로 나누지 않는 이유는 화면이 조직 하나에 숙소 하나를 보고 있고, 알림이
 * "무엇이 바뀌었다"가 아니라 "바뀌었으니 다시 조회하라"이기 때문이다. 숙소가 여럿이
 * 되면 화면이 자기 숙소 것만 골라 쓰면 된다 — 이벤트에 {@code propertyId} 가 실려 있다.
 *
 * <p><b>토큰은 헤더로 온다.</b> 브라우저의 {@code EventSource} 는 헤더를 붙이지 못해
 * 보통 토큰을 쿼리에 실는데, 그러면 접근 토큰이 URL 로 새어 로그와 이력에 남는다.
 * ADR 0006 이 리프레시를 쿠키로 감춘 이유가 무의미해진다. 그래서 화면은
 * {@code EventSource} 대신 {@code fetch} 스트림으로 붙는다. 근거는 ADR 0010.
 */
@RestController
@RequestMapping("/api/calendar/stream")
class CalendarStreamController {

    private final CalendarStreamHub hub;

    CalendarStreamController(CalendarStreamHub hub) {
        this.hub = hub;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        Long orgId = AuthenticatedUser.current()
                .orElseThrow(() -> new IllegalStateException(
                        "인증이 필요한 경로인데 주체가 없다. SecurityConfig 설정을 확인할 것."))
                .orgId();
        return hub.subscribe(orgId);
    }
}
