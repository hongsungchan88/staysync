package com.staysync.ops;

import com.staysync.booking.CheckOutFollowUps;
import com.staysync.ops.domain.TaskStatus;
import com.staysync.ops.domain.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 체크아웃 되돌리기가 청소 태스크를 거두는 자리(작업지시-20 9절).
 *
 * <p><b>"할 일"이면 지우고, 그 밖이면 막는다.</b> 진행 중·완료·막힘은 누군가 이미 손을 댄 것이라
 * 지우면 그 일의 기록이 사라진다. 태스크가 아직 없으면(Outbox 가 아직 안 배달했으면) 거둘 것이
 * 없다 — 늦게 온 이벤트는 {@code createCleaningFor} 가 지금 상태를 다시 읽어 버린다.
 *
 * <p>판매 단위의 DIRTY 는 되돌리지 않는다. 손님이 다시 방에 있으니 어차피 청소할 방이고,
 * 체크아웃 전 상태를 알 방법도 없다. 인박스 알림도 남긴다 — 무슨 일이 있었는지의 흔적이다.
 */
@Component
class CheckoutTaskWithdrawal implements CheckOutFollowUps {

    private static final Logger log = LoggerFactory.getLogger(CheckoutTaskWithdrawal.class);

    private final OpsTaskRepository tasks;

    CheckoutTaskWithdrawal(OpsTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Override
    @Transactional
    public void withdraw(Long reservationId) {
        tasks.findByReservationIdAndTaskType(reservationId, TaskType.CLEANING).ifPresent(task -> {
            if (task.getStatus() != TaskStatus.TODO) {
                throw new CleaningAlreadyStartedException(task.getStatus());
            }
            tasks.delete(task);
            log.info("체크아웃 되돌리기로 청소 태스크를 거뒀다. taskId={} reservationId={}",
                    task.getId(), reservationId);
        });
    }
}
