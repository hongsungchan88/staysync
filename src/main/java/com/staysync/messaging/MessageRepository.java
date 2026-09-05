package com.staysync.messaging;

import com.staysync.messaging.domain.Message;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByThreadIdOrderBySentAtAscIdAsc(Long threadId);

    /**
     * 이미 받은 메시지인지. {@code uq_message_external} 이 최종 방어선이고 이 확인은
     * 예외 대신 조용히 넘기기 위한 것이다.
     *
     * <p>중복은 오류가 아니라 정상 동작이다 — 채널의 웹훅은 최소 1회 전달이고,
     * 시뮬레이터는 일부러 같은 것을 여러 번 보낸다(11주차 결정).
     */
    boolean existsByThreadIdAndExternalId(Long threadId, String externalId);
}
