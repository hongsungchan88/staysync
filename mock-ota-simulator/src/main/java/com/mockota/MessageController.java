package com.mockota;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 메시징의 채널 표면. P4 14주차에 더했다.
 *
 * <p>{@code /api} 아래에 있으므로 {@link ChaosFilter} 의 악조건 주입과
 * {@link ApiKeyFilter} 의 자격 증명 검사를 그대로 받는다. 메시징만 무사통과시키면
 * 발송 실패의 재시도·{@code DEAD} 처리를 검증할 수 없다.
 *
 * <p>받은 것을 <b>해석하지 않는다.</b> 본문이 비었는지, 변수가 남아 있는지 보지 않는다.
 * 그건 우리 쪽이 막아야 하는 것이고({@code {{guestName}}} 이 그대로 나가면 안 된다),
 * 여기서 걸러 주면 그 검증이 사라진다.
 */
@RestController
@RequestMapping("/api/messages")
class MessageController {

    private final MessageStore messages;

    MessageController(MessageStore messages) {
        this.messages = messages;
    }

    /** 게스트가 보낸 메시지. 우리 쪽에서 보면 수신이다. */
    @GetMapping
    List<MockMessage> listInbound() {
        return messages.inbound();
    }

    /**
     * 우리가 보낸 메시지를 받는다.
     *
     * <p>202 로 답한다. {@code /api/ari} 와 같은 이유다 — 받았다는 것과 게스트에게
     * 전달됐다는 것은 다르고, 200 으로 답하면 워커가 "전달됐다"로 읽을 여지가 생긴다.
     */
    @PostMapping
    ResponseEntity<MockMessage> send(@RequestBody MockMessage body) {
        MockMessage stored = new MockMessage(
                body.messageId(), body.threadId(), body.bookingId(),
                MockMessage.HOST, body.body(), Instant.now());
        messages.send(stored);
        return ResponseEntity.accepted().body(stored);
    }

    /** 우리가 보낸 것을 되돌려 준다. 무엇이 실제로 도착했는지 테스트가 여기서 본다. */
    @GetMapping("/sent")
    List<MockMessage> listSent() {
        return messages.sent();
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forget() {
        messages.clear();
    }
}
