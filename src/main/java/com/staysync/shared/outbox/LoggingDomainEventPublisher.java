package com.staysync.shared.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 소비자가 붙기 전까지 쓰는 기본 구현. 로그만 남긴다.
 *
 * <p>P3 에서 채널 워커를 만들 때는 {@link DomainEventPublisher} 를 구현한 빈에
 * {@code @Primary} 를 붙여 이 구현을 밀어낸다.
 *
 * <p>{@code @ConditionalOnMissingBean} 을 쓰지 않는다. 그 애너테이션은 자동 구성의
 * {@code @Bean} 메서드에서만 제대로 평가되고, 스캔되는 {@code @Component} 에 붙이면
 * 빈 등록 순서에 따라 조건이 먼저 판정되어 아무 구현도 남지 않을 수 있다.
 */
@Component
class LoggingDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingDomainEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("이벤트 발행(소비자 없음). type={} aggregate={}#{} payload={}",
                event.getEventType(), event.getAggregateType(), event.getAggregateId(),
                event.getPayload());
    }
}
