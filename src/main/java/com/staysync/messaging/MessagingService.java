package com.staysync.messaging;

import com.staysync.booking.ReservationBrief;
import com.staysync.booking.ReservationDirectory;
import com.staysync.channel.ChannelMessageGateway;
import com.staysync.channel.OutboundChannelMessage;
import com.staysync.messaging.domain.Message;
import com.staysync.messaging.domain.MessageSender;
import com.staysync.messaging.domain.MessageTemplate;
import com.staysync.messaging.domain.MessageThread;
import com.staysync.property.OwnedResources;
import com.staysync.property.UnitCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인박스가 쓰는 조회와 발송. messaging 모듈의 바깥 표면이다.
 *
 * <p><b>발송은 채널을 직접 부르지 않는다.</b> {@code sync_job} 을 만들고 끝낸다 —
 * 12주차의 재시도·백오프·{@code DEAD} 와 연결별 순서 보장이 전부 거기 있고, 메시지
 * 전용 경로를 만들면 그 정책이 두 벌이 된다(작업지시 11 의 5절 2번).
 *
 * <p>모든 진입점이 {@link OwnedResources} 를 거친다. 남의 조직 스레드는 404 다.
 */
@Service
public class MessagingService {

    private static final Logger log = LoggerFactory.getLogger(MessagingService.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MessageThreadRepository threads;
    private final MessageRepository messages;
    private final MessageTemplateRepository templates;
    private final ReservationDirectory reservations;
    private final ChannelMessageGateway gateway;
    private final OwnedResources owned;
    private final UnitCatalog unitCatalog;

    MessagingService(MessageThreadRepository threads, MessageRepository messages,
                     MessageTemplateRepository templates, ReservationDirectory reservations,
                     ChannelMessageGateway gateway, OwnedResources owned,
                     UnitCatalog unitCatalog) {
        this.threads = threads;
        this.messages = messages;
        this.templates = templates;
        this.reservations = reservations;
        this.gateway = gateway;
        this.owned = owned;
        this.unitCatalog = unitCatalog;
    }

    // --- 조회 -----------------------------------------------------------------

    /** 스레드 하나와 그 대화, 예약 요약. 화면 한 장이 필요로 하는 것 전부다. */
    public record ThreadView(MessageThread thread, List<Message> messages,
                             ReservationBrief reservation, boolean messagingSupported) {
    }

    /** 목록에 필요한 것만. 대화 본문은 담지 않는다 — 스레드 수만큼 커진다. */
    public record ThreadSummary(MessageThread thread, ReservationBrief reservation) {
    }

    @Transactional(readOnly = true)
    public List<ThreadSummary> listThreads(Long orgId) {
        List<Long> propertyIds = owned.propertyIdsOf(orgId);
        if (propertyIds.isEmpty()) {
            return List.of();
        }
        List<MessageThread> found = threads.findAllOf(propertyIds);
        Map<Long, ReservationBrief> byId = reservationsOf(found);

        List<ThreadSummary> result = new ArrayList<>();
        for (MessageThread thread : found) {
            // 예약이 아직 붙지 않은 스레드가 있다. 채널이 예약보다 메시지를 먼저
            // 보내는 경우이고, Map.of() 는 null 키 조회에서 NPE 를 던진다.
            result.add(new ThreadSummary(thread, thread.getReservationId() == null
                    ? null : byId.get(thread.getReservationId())));
        }
        return result;
    }

    /**
     * 대화를 연다. <b>여는 것이 곧 읽음 처리다.</b>
     *
     * <p>읽음을 따로 누르게 하면 미읽음 표시가 실제와 어긋난 채 쌓인다. 실시간
     * 동기화는 하지 않는다 — 다른 탭의 표시는 새로고침으로 맞춘다(작업지시 11 의 3절).
     */
    @Transactional
    public ThreadView openThread(Long threadId, Long orgId) {
        MessageThread thread = load(threadId, orgId);
        thread.markRead();
        return new ThreadView(
                thread,
                messages.findByThreadIdOrderBySentAtAscIdAsc(threadId),
                thread.getReservationId() == null
                        ? null : reservations.find(thread.getReservationId()).orElse(null),
                supportsMessaging(thread));
    }

    // --- 발송 -----------------------------------------------------------------

    /**
     * 사람이 쓴 답을 보낸다.
     *
     * <p>{@code templateId} 가 있으면 그 본문을 치환해 쓴다. <b>채울 수 없는 변수가
     * 하나라도 있으면 예외로 막는다</b> — 치환되지 않은 변수가 그대로 나간 메시지는
     * 되돌릴 수 없다.
     */
    @Transactional
    public Message send(Long threadId, Long orgId, String body, Long templateId) {
        MessageThread thread = load(threadId, orgId);
        String resolved = templateId == null
                ? requireBody(body)
                : renderTemplate(templateId, orgId, thread);
        return dispatch(thread, MessageSender.HOST, resolved);
    }

    /**
     * 실제로 내보낸다. 자동 발송도 이 경로로 온다.
     *
     * <p>메시지를 먼저 저장하고 작업을 만든다. 순서가 반대면 채널에는 갔는데 우리
     * 대화에는 없는 메시지가 생기고, 호스트는 같은 말을 한 번 더 쓴다.
     *
     * <p><b>메시징을 지원하지 않는 채널이면 저장도 하지 않는다.</b> 보낼 수 없다는
     * 사실을 알면서 대화에만 남기면 화면이 "보냈다"고 거짓말을 한다.
     */
    @Transactional
    public Message dispatch(MessageThread thread, MessageSender sender, String body) {
        Long connectionId = connectionIdOf(thread);
        if (connectionId == null || !gateway.supportsMessaging(connectionId)) {
            throw new ChannelMessagingUnsupportedException(thread.getChannelCode());
        }

        Message saved = messages.saveAndFlush(Message.outbound(thread.getId(), sender, body));
        thread.recordOutbound(saved.getSentAt());

        String channelBookingId = thread.getReservationId() == null ? null
                : reservations.find(thread.getReservationId())
                        .map(ReservationBrief::channelBookingId).orElse(null);

        boolean queued = gateway.enqueueSend(connectionId,
                new OutboundChannelMessage(thread.getExternalId(), channelBookingId, body),
                idempotencyKey(saved.getId()));
        if (!queued) {
            // 같은 발송이 이미 대기 중이다. 메시지 식별자가 키라 실제로는 일어나지
            // 않지만, 조용히 넘어가면 안 보내진 것을 보낸 것으로 남긴다.
            log.warn("발송 작업이 만들어지지 않았다. threadId={} messageId={}",
                    thread.getId(), saved.getId());
        }
        return saved;
    }

    // --- 템플릿 ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MessageTemplate> listTemplates(Long orgId) {
        return templates.findByOrgIdOrderByIdAsc(orgId);
    }

    @Transactional
    public MessageTemplate createTemplate(Long orgId, String code, String name, String body) {
        return templates.save(new MessageTemplate(orgId, code, name, body));
    }

    /**
     * 템플릿을 이 스레드의 값으로 치환한다.
     *
     * <p>예약이 붙지 않은 스레드는 게스트 이름도 날짜도 없다. 그때는 치환할 수 없는
     * 변수가 남고 발송이 막힌다 — 그것이 옳다. 빈칸으로 내보내면 되돌릴 수 없다.
     */
    @Transactional(readOnly = true)
    public String renderTemplate(Long templateId, Long orgId, MessageThread thread) {
        MessageTemplate template = templates.findById(templateId)
                .filter(t -> t.getOrgId().equals(orgId))
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
        return TemplateRenderer.render(template.getBody(), variablesOf(thread));
    }

    /**
     * 치환에 쓸 값. 계획서 8.5 가 적은 만큼이다 — 게스트 이름, 체크인·체크아웃, 숙소명.
     *
     * <p><b>연락처를 넣지 않는다.</b> 넣으면 메시지 본문이 평문 연락처를 담는 두 번째
     * 경로가 된다(ADR 0007).
     */
    public Map<String, String> variablesOf(MessageThread thread) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("propertyName", unitCatalog.propertyNameOf(thread.getPropertyId()));

        Optional<ReservationBrief> reservation = thread.getReservationId() == null
                ? Optional.empty() : reservations.find(thread.getReservationId());
        reservation.ifPresent(r -> {
            values.put("guestName", r.guestName());
            values.put("checkIn", r.checkIn().format(DATE));
            values.put("checkOut", r.checkOut().format(DATE));
            values.put("confirmationCode", r.confirmationCode());
        });
        return values;
    }

    /** 예약 하나로 치환값을 만든다. 자동 발송이 쓴다. */
    public Map<String, String> variablesOf(ReservationBrief reservation) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("propertyName", unitCatalog.propertyNameOf(reservation.propertyId()));
        values.put("guestName", reservation.guestName());
        values.put("checkIn", reservation.checkIn().format(DATE));
        values.put("checkOut", reservation.checkOut().format(DATE));
        values.put("confirmationCode", reservation.confirmationCode());
        return values;
    }

    /** 채널 식별자로 스레드를 찾는다. 테스트와 자동 발송이 쓴다. */
    @Transactional(readOnly = true)
    public Optional<MessageThread> threadOfExternal(String channelCode, String externalId) {
        return threads.findByChannelCodeAndExternalId(channelCode, externalId);
    }

    /** 예약에 붙은 스레드. 자동 발송이 쓴다. 없으면 비어 온다. */
    @Transactional(readOnly = true)
    public Optional<MessageThread> threadOfReservation(Long reservationId) {
        return threads.findByReservationId(reservationId);
    }

    // --- 내부 -----------------------------------------------------------------

    private MessageThread load(Long threadId, Long orgId) {
        MessageThread thread = threads.findById(threadId)
                .orElseThrow(() -> new ThreadNotFoundException(threadId));
        if (!owned.ownsProperty(thread.getPropertyId(), orgId)) {
            // 남의 조직 스레드다. 403 으로 답하면 식별자를 훑어 존재를 알아낼 수 있다.
            throw new ThreadNotFoundException(threadId);
        }
        return thread;
    }

    private boolean supportsMessaging(MessageThread thread) {
        Long connectionId = connectionIdOf(thread);
        return connectionId != null && gateway.supportsMessaging(connectionId);
    }

    /** 스레드의 채널 코드로 연결을 찾는다. 같은 숙소에 같은 채널 코드는 하나다(V1). */
    private Long connectionIdOf(MessageThread thread) {
        return gateway.messagingConnections().stream()
                .filter(c -> c.propertyId().equals(thread.getPropertyId())
                        && c.channelCode().equals(thread.getChannelCode()))
                .map(com.staysync.channel.ChannelMessagingConnection::connectionId)
                .findFirst()
                .orElse(null);
    }

    private Map<Long, ReservationBrief> reservationsOf(List<MessageThread> found) {
        List<Long> ids = found.stream()
                .map(MessageThread::getReservationId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, ReservationBrief> byId = new HashMap<>();
        for (ReservationBrief brief : reservations.findAll(ids)) {
            byId.put(brief.id(), brief);
        }
        return byId;
    }

    private static String requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("메시지 본문은 비어 있을 수 없습니다.");
        }
        return body;
    }

    /**
     * 발송 작업의 멱등성 키. 메시지 하나에 발송 하나다.
     *
     * <p>본문이 아니라 메시지 식별자를 쓴다. 같은 말을 두 번 보내는 것은 정상이고,
     * 본문을 키로 삼으면 두 번째가 중복으로 걸려 조용히 사라진다.
     */
    private static String idempotencyKey(Long messageId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("message:" + messageId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
        }
    }
}
