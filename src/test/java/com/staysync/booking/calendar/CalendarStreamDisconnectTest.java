package com.staysync.booking.calendar;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 확인-05 3절 D. <b>끊긴 브라우저 하나가 ERROR 를 남기지 않는다.</b>
 *
 * <p>허브는 끊김을 잡아 DEBUG 로 남기고 있었는데도 로그에 ERROR 와 스택 트레이스가
 * 찍혔다. {@code completeWithError} 가 그 예외를 <b>서블릿 오류 경로로 다시 올려</b>
 * {@code GlobalExceptionHandler} 까지 닿았기 때문이다. 브라우저 확인에서 드러났다 —
 * 자동 테스트도 화면도 정상이었다.
 *
 * <p>스프링을 띄우지 않는다. 확인할 것은 "실패한 구독자를 어떻게 닫는가" 하나뿐이다.
 */
class CalendarStreamDisconnectTest {

    @Test
    @DisplayName("끊긴 구독자는 오류가 아니라 완료로 닫는다")
    void 끊긴_구독자는_완료로_닫는다() throws Exception {
        CalendarStreamHub hub = new CalendarStreamHub();
        SseEmitter 끊긴_구독자 = mock(SseEmitter.class);
        doThrow(new IOException("현재 연결은 사용자의 호스트 시스템의 소프트웨어에 의해 중단되었습니다"))
                .when(끊긴_구독자).send(any(SseEmitter.SseEventBuilder.class));

        hub.send(끊긴_구독자, "calendar", Map.of("type", "RESERVATION_CONFIRMED"));

        // 이미 상대가 없는 연결에 오류 응답을 만들 이유가 없다. 만들려 들면 그 예외가
        // 오류 경로로 올라가 ERROR 로 찍히고, 진짜 오류를 덮는다.
        verify(끊긴_구독자).complete();
        verify(끊긴_구독자, never()).completeWithError(any());
    }
}
