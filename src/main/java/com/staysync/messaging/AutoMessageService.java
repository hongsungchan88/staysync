package com.staysync.messaging;

import com.staysync.booking.ReservationBrief;
import com.staysync.booking.ReservationDirectory;
import com.staysync.messaging.domain.MessageRule;
import com.staysync.messaging.domain.MessageTrigger;
import com.staysync.property.OwnedResources;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자동 발송의 판단이 모이는 곳. 계획서 8.5 의 트리거 넷이 전부 여기를 지난다.
 *
 * <p><b>세 가지를 거른다. 셋 다 잘못된 메시지가 게스트에게 나가는 경우이고, 나간
 * 뒤에는 되돌릴 수 없다.</b>
 *
 * <ol>
 *   <li><b>중복</b> — {@code uq_dispatch_once} 가 막는다. Outbox 는 최소 1회 전달이고
 *       스케줄러는 재기동하면 같은 날짜를 다시 훑는다</li>
 *   <li><b>취소된 예약</b> — 체크인 하루 전 알림이 취소된 예약에 나가는 것이 이
 *       기능의 가장 흔한 사고다</li>
 *   <li><b>치환 실패</b> — {@code TemplateRenderer} 가 막는다. 변수가 그대로 나간
 *       메시지는 되돌릴 수 없다</li>
 * </ol>
 *
 * <p><b>실제 발송은 {@link AutoMessageDispatcher} 가 한다.</b> 한 건이 곧 한
 * 트랜잭션이어야 하는데, 같은 클래스 안에서 {@code @Transactional} 메서드를 부르면
 * 프록시를 거치지 않아 그 경계가 아예 걸리지 않는다. 이 프로젝트에서 반복해 적어 둔
 * 함정이고, 여기서는 <b>보내지 못한 안내가 "이미 보냈다"로 남는</b> 모양으로 나온다.
 */
@Service
public class AutoMessageService {

    private static final Logger log = LoggerFactory.getLogger(AutoMessageService.class);

    private final MessageRuleRepository rules;
    private final ReservationDirectory reservations;
    private final AutoMessageDispatcher dispatcher;
    private final OwnedResources owned;

    AutoMessageService(MessageRuleRepository rules, ReservationDirectory reservations,
                       AutoMessageDispatcher dispatcher, OwnedResources owned) {
        this.rules = rules;
        this.reservations = reservations;
        this.dispatcher = dispatcher;
        this.owned = owned;
    }

    /**
     * 그 예약에 그 트리거의 규칙을 적용한다.
     *
     * <p>규칙이 여럿이면 각각 한 번씩 나간다. 하나가 실패해도 나머지는 나간다 —
     * 템플릿 하나에 변수가 빠졌다고 그날 안내가 통째로 멈추면 안 된다.
     *
     * @return 실제로 보낸 건수
     */
    public int apply(MessageTrigger trigger, Long reservationId) {
        Optional<ReservationBrief> found = reservations.find(reservationId);
        if (found.isEmpty()) {
            return 0;
        }
        ReservationBrief reservation = found.get();

        if (!reservation.isActive()) {
            // 취소·만료된 예약이다. 체크인 하루 전 알림이 취소된 예약에 나가는 것이
            // 이 기능의 가장 흔한 사고다.
            log.debug("살아 있지 않은 예약이라 자동 발송하지 않는다. reservationId={} status={}",
                    reservationId, reservation.status());
            return 0;
        }

        Long orgId = owned.orgIdOfProperty(reservation.propertyId()).orElse(null);
        if (orgId == null) {
            return 0;
        }

        int sent = 0;
        for (MessageRule rule : rules.findEnabled(orgId, trigger)) {
            if (!rule.appliesTo(reservation.propertyId())) {
                continue;
            }
            if (dispatcher.sendOnce(rule, reservation)) {
                sent++;
            }
        }
        return sent;
    }

    // --- 규칙 관리 ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MessageRule> listRules(Long orgId) {
        return rules.findByOrgIdOrderByIdAsc(orgId);
    }

    @Transactional
    public MessageRule createRule(Long orgId, Long propertyId, MessageTrigger trigger,
                                  Long templateId) {
        return rules.save(new MessageRule(orgId, propertyId, trigger, templateId));
    }

    @Transactional
    public MessageRule changeEnabled(Long ruleId, Long orgId, boolean enabled) {
        MessageRule rule = rules.findById(ruleId)
                .filter(r -> r.getOrgId().equals(orgId))
                .orElseThrow(() -> new MessageRuleNotFoundException(ruleId));
        rule.changeEnabled(enabled);
        return rule;
    }
}
