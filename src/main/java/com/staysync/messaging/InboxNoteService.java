package com.staysync.messaging;

import com.staysync.booking.ReservationBrief;
import com.staysync.booking.ReservationDirectory;
import com.staysync.messaging.domain.Message;
import com.staysync.messaging.domain.MessageThread;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InboxNotes} 구현.
 *
 * <p><b>{@code MessagingService.dispatch} 를 쓰지 않는다.</b> 저쪽은 채널이 메시징을
 * 지원하는지 보고 {@code sync_job} 을 만든다. 알림은 우리끼리 보는 것이라 채널로 나갈
 * 이유가 없고, 나가면 게스트가 청소 일정 통지를 받는다. iCal 예약처럼 메시징을
 * 지원하지 않는 채널에서도 알림은 남아야 한다는 점도 저 경로로는 안 된다 —
 * 거기서는 예외가 난다.
 *
 * <p>전파는 기본값 {@code REQUIRED} 다. 태스크 생성과 같은 트랜잭션에서 돈다.
 */
@Service
@Transactional
class InboxNoteService implements InboxNotes {

    private static final Logger log = LoggerFactory.getLogger(InboxNoteService.class);

    private final MessageThreadRepository threads;
    private final MessageRepository messages;
    private final ReservationDirectory reservations;

    InboxNoteService(MessageThreadRepository threads, MessageRepository messages,
                     ReservationDirectory reservations) {
        this.threads = threads;
        this.messages = messages;
        this.reservations = reservations;
    }

    @Override
    public boolean note(Long reservationId, String body) {
        if (reservationId == null || body == null || body.isBlank()) {
            return false;
        }
        Optional<ReservationBrief> found = reservations.find(reservationId);
        if (found.isEmpty()) {
            log.debug("예약을 찾지 못해 알림을 남기지 않는다. reservationId={}", reservationId);
            return false;
        }

        MessageThread thread = threadFor(found.get());
        Message saved = messages.saveAndFlush(Message.systemNote(thread.getId(), body));
        thread.receiveSystemNote(saved.getSentAt());
        return true;
    }

    /**
     * 예약에 붙은 스레드. 없으면 만든다.
     *
     * <p>14주차 {@code AutoMessageDispatcher.threadFor} 와 같은 규칙이다. 채널 측 대화
     * 식별자는 비워 둔다 — 나중에 게스트 메시지가 오면 {@code MessageIngestService} 가
     * 이 스레드를 찾아 채운다. 새로 만들면 같은 게스트와의 대화가 둘로 갈라진다.
     */
    private MessageThread threadFor(ReservationBrief reservation) {
        return threads.findByReservationId(reservation.id())
                .orElseGet(() -> threads.saveAndFlush(new MessageThread(
                        reservation.propertyId(), reservation.channelCode(), null,
                        reservation.id(), reservation.guestId(),
                        reservation.confirmationCode())));
    }
}
