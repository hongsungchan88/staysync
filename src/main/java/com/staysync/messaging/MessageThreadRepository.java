package com.staysync.messaging;

import com.staysync.messaging.domain.MessageThread;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MessageThreadRepository extends JpaRepository<MessageThread, Long> {

    /** 스레드의 신원. {@code uq_thread_external} 이 이 경로다. */
    Optional<MessageThread> findByChannelCodeAndExternalId(String channelCode, String externalId);

    Optional<MessageThread> findByReservationId(Long reservationId);

    /**
     * 채널 식별자가 아직 없는 스레드. 자동 발송이 게스트보다 먼저 만든 것이다.
     *
     * <p>게스트 메시지가 오면 이 스레드를 찾아 식별자를 채운다. 새로 만들면 같은
     * 대화가 둘로 갈라진다.
     */
    Optional<MessageThread> findByReservationIdAndExternalIdIsNull(Long reservationId);

    /**
     * 조직의 스레드 목록. 최근 대화가 위로 온다.
     *
     * <p>{@code message_thread} 에 {@code org_id} 가 없어 숙소로 좁힌다.
     * {@code unit}·{@code rate_plan}·{@code overbooking_conflict} 와 같은 자리다.
     *
     * <p>정렬은 {@code last_message_at} 내림차순이다. 계획서 8.5 는 "체크인 임박 순"도
     * 적었는데 그건 화면이 고를 수 있게 두었다 — 정렬 기준을 서버가 하나로 고정하면
     * 답을 기다리는 대화가 체크인이 먼 순서에 묻힌다.
     */
    @Query("""
            select t from MessageThread t
            where t.propertyId in :propertyIds
            order by t.lastMessageAt desc nulls last, t.id desc
            """)
    List<MessageThread> findAllOf(@Param("propertyIds") List<Long> propertyIds);
}
