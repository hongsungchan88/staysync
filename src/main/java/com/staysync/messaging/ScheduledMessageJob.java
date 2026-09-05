package com.staysync.messaging;

import com.staysync.booking.ReservationBrief;
import com.staysync.booking.ReservationDirectory;
import com.staysync.messaging.domain.MessageTrigger;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 시간 기반 자동 발송. 계획서 8.5 의 트리거 셋이다.
 *
 * <pre>
 *   BEFORE_CHECK_IN   내일 체크인하는 예약
 *   ON_CHECK_OUT      오늘 체크아웃하는 예약
 *   AFTER_CHECK_OUT   어제 체크아웃한 예약
 * </pre>
 *
 * <p>하루 한 번 오전 9시에 돈다. 안내가 새벽에 가면 게스트가 깬다 — 이 기능은 재고와
 * 달리 사람이 읽는 것이라 도는 시각 자체가 설계다.
 *
 * <p><b>재기동해도 두 번 가지 않는다.</b> 같은 날 다시 돌면 같은 예약이 다시 대상이
 * 되지만 {@code uq_dispatch_once} 가 막는다. 스케줄러가 "오늘 이미 돌았나"를 따로
 * 기억할 필요가 없는 이유다.
 *
 * <p><b>15초 사슬 밖이다.</b> 하루 한 번 도는 배치이고 재고를 건드리지 않는다.
 *
 * <p>ShedLock 을 쓰지 않는다. 인스턴스가 하나뿐이고, 둘이 되어 동시에 돌아도
 * 유일 제약이 중복 발송을 막는다. 그때는 한쪽이 예외를 보고 넘어갈 뿐이다.
 */
@Component
public class ScheduledMessageJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMessageJob.class);

    /** 시간 기반 트리거 셋. 예약 확정은 Outbox 소비자가 맡는다. */
    private static final List<MessageTrigger> TIME_BASED = List.of(
            MessageTrigger.BEFORE_CHECK_IN,
            MessageTrigger.ON_CHECK_OUT,
            MessageTrigger.AFTER_CHECK_OUT);

    private final ReservationDirectory reservations;
    private final AutoMessageService auto;

    ScheduledMessageJob(ReservationDirectory reservations, AutoMessageService auto) {
        this.reservations = reservations;
        this.auto = auto;
    }

    @Scheduled(cron = "${staysync.messaging.schedule-cron:0 0 9 * * *}", zone = "Asia/Seoul")
    public void run() {
        runFor(LocalDate.now());
    }

    /**
     * 그날 기준으로 셋을 전부 돈다. 테스트가 날짜를 직접 준다.
     *
     * @return 실제로 보낸 건수
     */
    public int runFor(LocalDate today) {
        int sent = 0;
        for (MessageTrigger trigger : TIME_BASED) {
            sent += runTrigger(trigger, today);
        }
        return sent;
    }

    /**
     * 트리거 하나.
     *
     * <p>대상 날짜는 <b>부호가 뒤집힌다.</b> 체크인 하루 전 규칙은 오늘 기준
     * <i>내일</i> 체크인하는 예약이 대상이다. {@code dayOffset} 이 -1 이므로 빼면
     * 어제가 되어 아무도 안 나온다.
     */
    public int runTrigger(MessageTrigger trigger, LocalDate today) {
        LocalDate target = today.minusDays(trigger.dayOffset());
        List<ReservationBrief> targets = trigger.basedOnCheckOut()
                ? reservations.activeByCheckOut(target)
                : reservations.activeByCheckIn(target);

        int sent = 0;
        for (ReservationBrief reservation : targets) {
            try {
                sent += auto.apply(trigger, reservation.id());
            } catch (RuntimeException e) {
                // 하나가 실패해도 나머지는 나가야 한다. 템플릿 하나에 변수가 빠졌다고
                // 그날 안내가 통째로 멈추면 안 된다.
                log.warn("자동 발송 대상 하나를 건너뛴다. trigger={} reservationId={} 사유={}",
                        trigger, reservation.id(), e.toString());
            }
        }
        if (sent > 0) {
            log.info("시간 기반 자동 발송. trigger={} 대상일={} 보낸건수={}", trigger, target, sent);
        }
        return sent;
    }
}
