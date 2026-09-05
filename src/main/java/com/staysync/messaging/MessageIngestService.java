package com.staysync.messaging;

import com.staysync.booking.ReservationBrief;
import com.staysync.booking.ReservationDirectory;
import com.staysync.channel.InboundChannelMessage;
import com.staysync.messaging.domain.Message;
import com.staysync.messaging.domain.MessageThread;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널에서 들어온 메시지를 받아들인다.
 *
 * <p><b>수신 멱등성이 이 클래스의 핵심이다.</b> 같은 메시지가 두 번 와도 한 건이어야
 * 한다. 채널의 웹훅은 최소 1회 전달이고, 폴링은 같은 목록을 주기마다 다시 읽는다 —
 * 중복이 정상이고 거르는 것은 우리 쪽 일이다.
 *
 * <p>거르는 자리가 둘이다. {@link MessageRepository#existsByThreadIdAndExternalId} 가
 * 평소를 맡고 {@code uq_message_external} 이 경합을 맡는다. 확인만 두면 동시에 들어온
 * 둘이 통과하고, 제약만 두면 정상 동작인 중복이 예외로 올라온다.
 *
 * <p>스레드를 예약에 잇는 것은 <b>가능하면</b> 한다. 채널이 예약보다 메시지를 먼저
 * 보내는 경우가 있고, 그때 대화를 버리면 게스트의 문의가 사라진다.
 */
@Service
public class MessageIngestService {

    private static final Logger log = LoggerFactory.getLogger(MessageIngestService.class);

    private final MessageThreadRepository threads;
    private final MessageRepository messages;
    private final ReservationDirectory reservations;

    MessageIngestService(MessageThreadRepository threads, MessageRepository messages,
                         ReservationDirectory reservations) {
        this.threads = threads;
        this.messages = messages;
        this.reservations = reservations;
    }

    /**
     * 메시지 한 통을 받아들인다.
     *
     * @param propertyId 이 연결이 붙은 숙소. 예약을 못 찾아도 스레드는 숙소에 붙는다
     * @return 새로 저장했으면 true. 이미 아는 메시지면 false
     */
    @Transactional
    public boolean ingest(Long propertyId, String channelCode, InboundChannelMessage incoming) {
        MessageThread thread = threadOf(propertyId, channelCode, incoming);

        if (messages.existsByThreadIdAndExternalId(thread.getId(), incoming.externalMessageId())) {
            log.debug("이미 받은 메시지라 넘긴다. channel={} messageId={}",
                    channelCode, incoming.externalMessageId());
            return false;
        }
        try {
            messages.saveAndFlush(Message.inbound(thread.getId(),
                    incoming.externalMessageId(), incoming.body(), incoming.sentAt()));
        } catch (DataIntegrityViolationException e) {
            // uq_message_external 이 막았다. 확인과 저장 사이에 같은 메시지가 하나 더
            // 들어온 것이고, 막고 싶었던 것이 정확히 그것이므로 오류로 올리지 않는다.
            log.debug("같은 메시지가 동시에 들어와 하나만 남긴다. messageId={}",
                    incoming.externalMessageId());
            return false;
        }
        thread.receiveGuestMessage(incoming.sentAt());
        return true;
    }

    /**
     * 스레드를 찾거나 만든다.
     *
     * <p>예약을 찾지 못해도 스레드는 만든다. 나중에 예약이 들어오면
     * {@link MessageThread#attachReservation} 이 이어 붙인다.
     */
    private MessageThread threadOf(Long propertyId, String channelCode,
                                   InboundChannelMessage incoming) {
        Optional<ReservationBrief> reservation = incoming.channelBookingId() == null
                ? Optional.empty()
                : reservations.findByChannel(channelCode, incoming.channelBookingId());

        return threads.findByChannelCodeAndExternalId(channelCode, incoming.externalThreadId())
                .map(existing -> {
                    reservation.ifPresent(r -> existing.attachReservation(r.id(), r.guestId()));
                    return existing;
                })
                .orElseGet(() -> threads.saveAndFlush(new MessageThread(
                        propertyId, channelCode, incoming.externalThreadId(),
                        reservation.map(ReservationBrief::id).orElse(null),
                        reservation.map(ReservationBrief::guestId).orElse(null),
                        reservation.map(ReservationBrief::confirmationCode).orElse(null))));
    }
}
