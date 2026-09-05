package com.staysync.messaging;

import com.staysync.messaging.domain.MessageDispatch;
import org.springframework.data.jpa.repository.JpaRepository;

interface MessageDispatchRepository extends JpaRepository<MessageDispatch, Long> {

    /**
     * 이미 보냈는지.
     *
     * <p><b>이 확인이 평소를 맡고 {@code uq_dispatch_once} 가 경합을 맡는다.</b>
     * 확인 없이 제약에만 기대면 중복 전달마다 예외가 나고, 그 예외는 트랜잭션을
     * rollback-only 로 만들어 잡아도 커밋이 막힌다(계획서 13.2 와 같은 함정).
     * 제약 없이 확인에만 기대면 확인과 저장 사이에 두 번째가 끼어든다. 둘 다 둔다.
     */
    boolean existsByRuleIdAndReservationId(Long ruleId, Long reservationId);
}
