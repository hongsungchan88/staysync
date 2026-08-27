package com.staysync.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 완료 조건 3~6, 8. 릴레이의 발행, 실패 처리, 순서, 상한.
 *
 * <p>발행 구현을 테스트용으로 갈아끼워 성공과 실패를 마음대로 만든다. 실제 발행 대상이
 * 아직 없으므로({@code LoggingDomainEventPublisher}) 이 방법 말고는 실패 경로를
 * 만들 수가 없다.
 *
 * <p>로그를 검증하려면 주기 실행이 멈춰 있어야 한다. Logback 로거는 JVM 전역이라
 * <b>다른 스프링 컨텍스트에서 도는 릴레이가 남긴 로그도 여기 appender 에 잡힌다.</b>
 * 그래서 이 클래스만이 아니라 모든 테스트 컨텍스트에서 꺼야 하며, build.gradle 의
 * test 태스크가 시스템 속성으로 일괄 적용한다.
 */
@SpringBootTest(properties = {
        // 포트와 디렉터리를 따로 쓴다. 아래 @TestConfiguration 이 컨텍스트 캐시 키를
        // 바꿔 이 테스트만 별도 컨텍스트를 갖게 되고, 그러면 내장 PostgreSQL 도 따로
        // 뜬다. 디렉터리가 같으면 뒤에 뜨는 쪽의 initdb 가 실패한다.
        // ApiTestBase 가 15434 를 쓰는 것과 같은 이유다.
        "staysync.embedded-postgres.port=15435",
        "staysync.embedded-postgres.data-directory=.localdb-relay"
})
@ActiveProfiles("local")
class OutboxRelayTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRecorder recorder;

    @Autowired
    private ControllablePublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    private ListAppender<ILoggingEvent> appender;
    private Logger relayLogger;

    @BeforeEach
    void 준비한다() {
        // 다른 테스트가 남긴 미발행 이벤트가 섞이면 건수 검증이 흔들린다.
        jdbc.update("DELETE FROM outbox_event");
        publisher.reset();

        relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
        appender = new ListAppender<>();
        appender.start();
        relayLogger.addAppender(appender);
    }

    @AfterEach
    void 정리한다() {
        relayLogger.detachAppender(appender);
        appender.stop();
        publisher.reset();
    }

    // --- 완료 조건 3 ---------------------------------------------------------

    @Test
    @DisplayName("미발행 이벤트를 발행하고 published_at 을 채운다")
    void 미발행_이벤트를_발행하고_시각을_남긴다() {
        Long id = 이벤트기록(1L, "RESERVATION_CONFIRMED");

        int published = relay.relayPending();

        assertThat(published).isEqualTo(1);
        assertThat(publishedAt(id)).isNotNull();
        assertThat(publisher.published()).hasSize(1);

        // 이미 발행한 것은 다시 나가지 않는다
        assertThat(relay.relayPending()).isZero();
    }

    // --- 완료 조건 4 ---------------------------------------------------------

    @Test
    @DisplayName("발행이 실패하면 retry_count 가 오르고 last_error 가 남는다")
    void 발행_실패는_재시도_횟수와_사유를_남긴다() {
        Long id = 이벤트기록(2L, "RESERVATION_CANCELLED");
        publisher.failWith("채널이 응답하지 않음");

        assertThat(relay.relayPending()).isZero();

        assertThat(retryCount(id)).isEqualTo(1);
        assertThat(lastError(id)).contains("채널이 응답하지 않음");
        // published_at 이 비어 있어야 다음 주기에 다시 시도된다
        assertThat(publishedAt(id)).isNull();

        // 다음 주기에 성공하면 정상 발행된다
        publisher.succeed();
        assertThat(relay.relayPending()).isEqualTo(1);
        assertThat(publishedAt(id)).isNotNull();
    }

    // --- 완료 조건 5 ---------------------------------------------------------

    @Test
    @DisplayName("같은 애그리게이트의 이벤트가 기록 순서대로 발행된다")
    void 같은_애그리게이트는_순서대로_발행된다() {
        // 취소가 확정보다 먼저 나가면 채널 쪽 상태가 뒤집힌다.
        이벤트기록(99L, "RESERVATION_CONFIRMED");
        이벤트기록(99L, "RESERVATION_DATES_CHANGED");
        이벤트기록(99L, "RESERVATION_CANCELLED");

        relay.relayPending();

        assertThat(publisher.publishedTypes())
                .containsExactly("RESERVATION_CONFIRMED",
                        "RESERVATION_DATES_CHANGED",
                        "RESERVATION_CANCELLED");
    }

    // --- 완료 조건 6 ---------------------------------------------------------

    @Test
    @DisplayName("한 주기 상한에 닿으면 경고를 남긴다")
    void 상한에_닿으면_경고를_남긴다() {
        for (int i = 0; i < OutboxRelay.BATCH_LIMIT + 1; i++) {
            이벤트기록(100L, "RESERVATION_CONFIRMED");
        }

        int published = relay.relayPending();

        assertThat(published).isEqualTo(OutboxRelay.BATCH_LIMIT);
        assertThat(warnMessages()).anySatisfy(m -> assertThat(m).contains("상한"));

        // 남은 한 건은 다음 주기에 나간다
        assertThat(relay.relayPending()).isEqualTo(1);
    }

    @Test
    void 상한에_닿지_않으면_경고가_없다() {
        이벤트기록(101L, "RESERVATION_CONFIRMED");

        relay.relayPending();

        assertThat(warnMessages())
                .as("정상 범위에서 경고가 나오면 진짜 경고가 묻힌다")
                .noneSatisfy(m -> assertThat(m).contains("상한"));
    }

    // --- 완료 조건 8 ---------------------------------------------------------

    @Test
    @DisplayName("재시도 상한에 닿은 이벤트는 조회에서 빠지고 경고는 한 번만 나온다")
    void 재시도_상한에_닿으면_조회에서_빠진다() {
        Long id = 이벤트기록(200L, "RESERVATION_CONFIRMED");
        publisher.failWith("계속 실패");

        // 상한까지 실패시킨다
        for (int i = 0; i < OutboxRelay.RETRY_LIMIT; i++) {
            relay.relayPending();
        }

        assertThat(retryCount(id)).isEqualTo(OutboxRelay.RETRY_LIMIT);
        // 행은 지우지 않는다. last_error 가 남아야 사람이 원인을 보고 다시 넣는다.
        assertThat(exists(id)).isTrue();
        assertThat(lastError(id)).contains("계속 실패");

        int 발행시도_상한도달후 = publisher.attempts();
        assertThat(relay.relayPending()).isZero();
        assertThat(publisher.attempts())
                .as("상한에 닿은 이벤트는 조회 대상에서 빠져 아예 시도되지 않는다")
                .isEqualTo(발행시도_상한도달후);

        // 경고는 상한에 닿는 그 한 번만. 매 주기 남기면 로그가 채워져 진짜 문제가 묻힌다.
        assertThat(errorMessages())
                .filteredOn(m -> m.contains("재시도 상한"))
                .hasSize(1);
    }

    @Test
    void 상한에_닿은_이벤트가_뒤의_정상_이벤트를_막지_않는다() {
        Long 막힌이벤트 = 이벤트기록(300L, "RESERVATION_CONFIRMED");
        publisher.failWith("영구 실패");
        for (int i = 0; i < OutboxRelay.RETRY_LIMIT; i++) {
            relay.relayPending();
        }
        publisher.succeed();

        Long 뒤이벤트 = 이벤트기록(301L, "RESERVATION_CANCELLED");

        assertThat(relay.relayPending()).isEqualTo(1);
        assertThat(publishedAt(뒤이벤트)).isNotNull();
        assertThat(publishedAt(막힌이벤트)).isNull();
    }

    // --- 헬퍼 ---------------------------------------------------------------

    private Long 이벤트기록(Long aggregateId, String eventType) {
        return recorder.record("RESERVATION", aggregateId, eventType,
                Map.of("reservationId", aggregateId)).getId();
    }

    private List<String> warnMessages() {
        return messagesAt(Level.WARN);
    }

    private List<String> errorMessages() {
        return messagesAt(Level.ERROR);
    }

    private List<String> messagesAt(Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private Object publishedAt(Long id) {
        return jdbc.queryForObject(
                "SELECT published_at FROM outbox_event WHERE id = ?", Object.class, id);
    }

    private int retryCount(Long id) {
        Integer c = jdbc.queryForObject(
                "SELECT retry_count FROM outbox_event WHERE id = ?", Integer.class, id);
        return c == null ? 0 : c;
    }

    private String lastError(Long id) {
        return jdbc.queryForObject(
                "SELECT last_error FROM outbox_event WHERE id = ?", String.class, id);
    }

    private boolean exists(Long id) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE id = ?", Integer.class, id);
        return c != null && c > 0;
    }

    /**
     * 성공과 실패를 조작할 수 있는 발행 구현.
     *
     * <p>{@code @Primary} 로 기본 구현({@code LoggingDomainEventPublisher})을 밀어낸다.
     */
    @TestConfiguration
    static class TestPublisherConfig {

        @Bean
        @Primary
        ControllablePublisher controllablePublisher() {
            return new ControllablePublisher();
        }
    }

    static class ControllablePublisher implements DomainEventPublisher {

        private final List<OutboxEvent> published = new ArrayList<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private volatile String failureMessage;

        @Override
        public void publish(OutboxEvent event) {
            attempts.incrementAndGet();
            if (failureMessage != null) {
                throw new IllegalStateException(failureMessage);
            }
            published.add(event);
        }

        void failWith(String message) {
            this.failureMessage = message;
        }

        void succeed() {
            this.failureMessage = null;
        }

        void reset() {
            published.clear();
            attempts.set(0);
            failureMessage = null;
        }

        List<OutboxEvent> published() {
            return List.copyOf(published);
        }

        List<String> publishedTypes() {
            return published.stream().map(OutboxEvent::getEventType).toList();
        }

        int attempts() {
            return attempts.get();
        }
    }
}
