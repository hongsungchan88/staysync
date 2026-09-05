package com.staysync.messaging.web;

import com.staysync.booking.ReservationBrief;
import com.staysync.messaging.AutoMessageService;
import com.staysync.messaging.MessagingService;
import com.staysync.messaging.domain.Message;
import com.staysync.messaging.domain.MessageRule;
import com.staysync.messaging.domain.MessageTemplate;
import com.staysync.messaging.domain.MessageThread;
import com.staysync.messaging.domain.MessageTrigger;
import com.staysync.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * 통합 인박스 API. 계획서 8.5 이고 경로는 8.1 의 {@code /inbox} 다.
 *
 * <p>조회와 변경이 전부 {@code OwnedResources} 를 거쳐 조직으로 좁혀진다. 소유가
 * 아니면 404 다.
 *
 * <p><b>게스트 연락처를 응답에 담지 않는다.</b> 이름까지다 — 인박스가 누구와의
 * 대화인지 보여 줘야 하고 템플릿이 그 값을 쓴다. 전화번호와 이메일은 담지 않는다
 * (ADR 0007).
 */
@RestController
@RequestMapping("/api")
class InboxController {

    private final MessagingService messaging;
    private final AutoMessageService auto;

    InboxController(MessagingService messaging, AutoMessageService auto) {
        this.messaging = messaging;
        this.auto = auto;
    }

    // --- 스레드 ----------------------------------------------------------------

    @GetMapping("/inbox")
    List<ThreadSummaryResponse> threads() {
        return messaging.listThreads(orgId()).stream()
                .map(s -> ThreadSummaryResponse.of(s.thread(), s.reservation()))
                .toList();
    }

    /** 대화를 연다. <b>여는 것이 곧 읽음 처리다.</b> */
    @GetMapping("/inbox/{threadId}")
    ThreadResponse thread(@PathVariable Long threadId) {
        MessagingService.ThreadView view = messaging.openThread(threadId, orgId());
        return new ThreadResponse(
                ThreadSummaryResponse.of(view.thread(), view.reservation()),
                view.messages().stream().map(MessageResponse::from).toList(),
                view.messagingSupported());
    }

    @PostMapping("/inbox/{threadId}/messages")
    MessageResponse send(@PathVariable Long threadId, @Valid @RequestBody SendRequest request) {
        return MessageResponse.from(
                messaging.send(threadId, orgId(), request.body(), request.templateId()));
    }

    // --- 템플릿과 규칙 ------------------------------------------------------------

    @GetMapping("/message-templates")
    List<TemplateResponse> templates() {
        return messaging.listTemplates(orgId()).stream().map(TemplateResponse::from).toList();
    }

    @PostMapping("/message-templates")
    TemplateResponse createTemplate(@Valid @RequestBody CreateTemplateRequest request) {
        return TemplateResponse.from(messaging.createTemplate(
                orgId(), request.code(), request.name(), request.body()));
    }

    @GetMapping("/message-rules")
    List<RuleResponse> rules() {
        return auto.listRules(orgId()).stream().map(RuleResponse::from).toList();
    }

    @PostMapping("/message-rules")
    RuleResponse createRule(@Valid @RequestBody CreateRuleRequest request) {
        return RuleResponse.from(auto.createRule(
                orgId(), request.propertyId(), request.trigger(), request.templateId()));
    }

    @PatchMapping("/message-rules/{ruleId}")
    RuleResponse changeRule(@PathVariable Long ruleId, @RequestBody ChangeRuleRequest request) {
        return RuleResponse.from(auto.changeEnabled(ruleId, orgId(), request.enabled()));
    }

    // --- 요청 ------------------------------------------------------------------

    /**
     * @param templateId 넘기면 그 템플릿을 이 스레드의 값으로 치환해 보낸다.
     *                   <b>채울 수 없는 변수가 있으면 422 로 막힌다</b>
     */
    record SendRequest(@Size(max = 4000) String body, Long templateId) {
    }

    record CreateTemplateRequest(@NotBlank @Size(max = 60) String code,
                                 @NotBlank @Size(max = 100) String name,
                                 @NotBlank String body) {
    }

    record CreateRuleRequest(Long propertyId, MessageTrigger trigger, Long templateId) {
    }

    record ChangeRuleRequest(boolean enabled) {
    }

    // --- 응답 ------------------------------------------------------------------

    /**
     * 목록 한 줄.
     *
     * <p>{@code checkIn} 을 담는 이유는 계획서 8.5 의 "체크인 임박 순 정렬" 때문이다.
     * 정렬은 화면이 고른다 — 서버가 하나로 고정하면 답을 기다리는 대화가 체크인이
     * 먼 순서에 묻힌다.
     */
    record ThreadSummaryResponse(Long id, String channelCode, String subject,
                                 int unreadCount, OffsetDateTime lastMessageAt,
                                 Long reservationId, String confirmationCode,
                                 String guestName, LocalDate checkIn, LocalDate checkOut,
                                 String reservationStatus) {

        static ThreadSummaryResponse of(MessageThread thread, ReservationBrief reservation) {
            return new ThreadSummaryResponse(
                    thread.getId(), thread.getChannelCode(), thread.getSubject(),
                    thread.getUnreadCount(), thread.getLastMessageAt(),
                    thread.getReservationId(),
                    reservation == null ? null : reservation.confirmationCode(),
                    reservation == null ? null : reservation.guestName(),
                    reservation == null ? null : reservation.checkIn(),
                    reservation == null ? null : reservation.checkOut(),
                    reservation == null ? null : reservation.status());
        }
    }

    /**
     * @param messagingSupported 거짓이면 화면이 입력창 대신 미지원 표시를 그린다.
     *                           계획서 6.1 이 말하는 {@code capabilities()} 의 세 번째
     *                           사용처다 — iCal 스레드가 여기 걸린다
     */
    record ThreadResponse(ThreadSummaryResponse thread, List<MessageResponse> messages,
                          boolean messagingSupported) {
    }

    record MessageResponse(Long id, String direction, String sender, String body,
                           OffsetDateTime sentAt) {

        static MessageResponse from(Message message) {
            return new MessageResponse(message.getId(), message.getDirection().name(),
                    message.getSender().name(), message.getBody(), message.getSentAt());
        }
    }

    record TemplateResponse(Long id, String code, String name, String body) {

        static TemplateResponse from(MessageTemplate template) {
            return new TemplateResponse(template.getId(), template.getCode(),
                    template.getName(), template.getBody());
        }
    }

    record RuleResponse(Long id, Long propertyId, String trigger, Long templateId,
                        boolean enabled) {

        static RuleResponse from(MessageRule rule) {
            return new RuleResponse(rule.getId(), rule.getPropertyId(),
                    rule.getTriggerType().name(), rule.getTemplateId(), rule.isEnabled());
        }
    }

    private static Long orgId() {
        return ((AuthenticatedUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal()).orgId();
    }
}
