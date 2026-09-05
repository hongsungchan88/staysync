package com.staysync.messaging;

import com.staysync.booking.ReservationBrief;
import com.staysync.messaging.domain.Message;
import com.staysync.messaging.domain.MessageDispatch;
import com.staysync.messaging.domain.MessageRule;
import com.staysync.messaging.domain.MessageSender;
import com.staysync.messaging.domain.MessageTemplate;
import com.staysync.messaging.domain.MessageThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동 발송 한 건의 <b>트랜잭션 경계</b>. {@link AutoMessageService} 가 부른다.
 *
 * <p>둘을 나눈 이유는 이 프로젝트에서 반복된 그것이다 — 같은 클래스 안에서
 * {@code @Transactional} 메서드를 부르면 스프링 프록시를 거치지 않아 트랜잭션이 아예
 * 걸리지 않는다. 여기서는 특히 나쁘게 나타났다. {@code REQUIRES_NEW} 가 걸리지 않아
 * 발송 기록만 제 트랜잭션으로 커밋되고, <b>치환에 실패해 보내지 못한 안내가 "이미
 * 보냈다"로 남았다.</b> 그 안내는 값을 채워 넣어도 영영 나가지 않는다.
 *
 * <p><b>한 건이 곧 한 트랜잭션이다.</b> 하루치를 한 트랜잭션에 묶으면 마지막 하나가
 * 실패할 때 이미 보낸 것들의 발송 기록까지 롤백된다 — 메시지는 채널로 나갔는데
 * 기록은 사라지고, 다음 주기에 같은 게스트에게 한 번 더 간다.
 */
@Component
class AutoMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AutoMessageDispatcher.class);

    private final MessageDispatchRepository dispatches;
    private final MessageTemplateRepository templates;
    private final MessageThreadRepository threads;
    private final MessagingService messaging;

    AutoMessageDispatcher(MessageDispatchRepository dispatches,
                          MessageTemplateRepository templates,
                          MessageThreadRepository threads,
                          MessagingService messaging) {
        this.dispatches = dispatches;
        this.templates = templates;
        this.threads = threads;
        this.messaging = messaging;
    }

    /**
     * 규칙 하나를 예약 하나에 적용한다.
     *
     * <p>발송 기록을 <b>먼저</b> 넣는다. 중복이 여기서 걸리면 아직 아무것도 보내지
     * 않은 상태다. 순서가 반대면 채널로 나간 뒤에야 중복이 드러난다.
     *
     * <p>뒤에서 실패하면 그 기록도 함께 롤백된다. 보내지 못한 것을 보낸 것으로 남기면
     * 그 안내는 영영 나가지 않는다.
     *
     * @return 실제로 보냈으면 true. 이미 보낸 건이면 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean sendOnce(MessageRule rule, ReservationBrief reservation) {
        if (dispatches.existsByRuleIdAndReservationId(rule.getId(), reservation.id())) {
            // 이미 보냈다. Outbox 의 중복 전달과 스케줄러 재기동이 만드는 정상 동작이다.
            log.debug("이미 보낸 규칙이라 넘긴다. ruleId={} reservationId={}",
                    rule.getId(), reservation.id());
            return false;
        }
        // 먼저 보고 나서 고른다. 예외를 잡는 방식으로 쓰면 안 된다 —
        // DataIntegrityViolationException 이 트랜잭션 경계를 넘는 순간 스프링이 이
        // 트랜잭션을 rollback-only 로 찍고, 잡아서 계속 진행해도 커밋 때
        // UnexpectedRollbackException 이 난다. 계획서 13.2 가 적어 둔 것과 같은 함정이다.
        //
        // 확인과 저장 사이에 두 번째가 끼어들면 uq_dispatch_once 가 막고 이 트랜잭션이
        // 죽는다. 그때는 다른 트랜잭션이 이미 보낸 것이므로 죽는 편이 맞다.
        dispatches.saveAndFlush(new MessageDispatch(rule.getId(), reservation.id(), null));

        MessageTemplate template = templates.findById(rule.getTemplateId()).orElseThrow(
                () -> new TemplateNotFoundException(rule.getTemplateId()));
        String body = TemplateRenderer.render(
                template.getBody(), messaging.variablesOf(reservation));

        Message message = messaging.dispatch(
                threadFor(reservation), MessageSender.SYSTEM, body);
        log.info("자동 발송했다. ruleId={} reservationId={} messageId={}",
                rule.getId(), reservation.id(), message.getId());
        return true;
    }

    /**
     * 예약에 붙은 스레드. 없으면 만든다.
     *
     * <p>게스트가 먼저 말을 걸지 않은 예약에도 안내는 나가야 한다. 그때는 채널 측
     * 대화 식별자를 아직 모르므로 비워 둔다 — 나중에 게스트 메시지가 오면
     * {@code MessageIngestService} 가 이 스레드를 찾아 채운다. 그렇게 하지 않으면
     * 같은 게스트와의 대화가 둘로 갈라진다.
     */
    private MessageThread threadFor(ReservationBrief reservation) {
        return threads.findByReservationId(reservation.id())
                .orElseGet(() -> threads.saveAndFlush(new MessageThread(
                        reservation.propertyId(), reservation.channelCode(), null,
                        reservation.id(), reservation.guestId(),
                        reservation.confirmationCode())));
    }
}
