package com.staysync.shared.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 발행된 이벤트의 흔적을 남긴다. 소비자 중 하나다.
 *
 * <p>P2 9주차에 실시간 갱신이 붙기 전까지는 유일한 소비자였고 "소비자 없음"을 적었다.
 * 이제는 {@link DomainEventPublishers} 가 이 구현과 실제 소비자들에게 함께 돌린다.
 *
 * <p>남겨 둔 이유는 <b>이벤트가 실제로 나갔는지 확인할 자리</b>가 필요하기 때문이다.
 * 화면이 갱신되지 않을 때, 이벤트가 발행되지 않은 것인지 소비자가 못 받은 것인지를
 * 이 로그로 가른다. 시끄러워지면 레벨을 낮추면 되고 지우지는 말 것.
 */
@Component
class LoggingDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingDomainEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("이벤트 발행. type={} aggregate={}#{} payload={}",
                event.getEventType(), event.getAggregateType(), event.getAggregateId(),
                event.getPayload());
    }
}
