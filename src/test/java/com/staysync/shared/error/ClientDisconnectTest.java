package com.staysync.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * 확인-05 완료 조건 7. SSE 끊김이 ERROR 로 올라오지 않는다.
 *
 * <p>브라우저가 캘린더 스트림을 끊으면 나는 정상적인 상황인데 {@code Exception} 폴백으로
 * 떨어져 스택 트레이스까지 ERROR 로 찍혔다. 5분에 3건이었고, 그만큼 <b>진짜 오류가
 * 묻힌다.</b>
 *
 * <p>스프링 컨텍스트를 띄우지 않는다. 검증할 것은 두 가지뿐이다 — 어느 핸들러가 골라지는가,
 * 그 핸들러가 어느 레벨로 남기는가. MockMvc 로는 끊긴 연결을 만들 수가 없다.
 */
class ClientDisconnectTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger handlerLogger;

    @BeforeEach
    void 준비한다() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void 정리한다() {
        handlerLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("SSE 끊김은 폴백이 아니라 전용 핸들러가 받는다")
    void 전용_핸들러가_받는다() {
        ExceptionHandlerMethodResolver resolver =
                new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

        Method handler = resolver.resolveMethod(끊김());

        assertThat(handler).isNotNull();
        assertThat(handler.getName())
                .as("폴백으로 떨어지면 500 응답을 쓰려다 같은 예외가 다시 난다")
                .isEqualTo("handleClientDisconnected");
    }

    @Test
    @DisplayName("SSE 끊김은 ERROR 로 남지 않는다")
    void ERROR로_남지_않는다() {
        new GlobalExceptionHandler().handleClientDisconnected(끊김());

        assertThat(appender.list)
                .as("정상적인 종료가 ERROR 면 진짜 오류가 묻힌다")
                .allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.DEBUG));
    }

    private static AsyncRequestNotUsableException 끊김() {
        return new AsyncRequestNotUsableException(
                "ServletOutputStream failed to flush",
                new IOException("현재 연결은 사용자의 호스트 시스템의 소프트웨어에 의해 중단되었습니다"));
    }
}
