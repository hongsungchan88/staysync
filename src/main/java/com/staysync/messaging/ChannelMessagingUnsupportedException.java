package com.staysync.messaging;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 그 채널로는 메시지를 보낼 수 없다. iCal 이 그렇다.
 *
 * <p><b>대화에 남기지도 않는다.</b> 보낼 수 없다는 사실을 알면서 우리 쪽에만 저장하면
 * 화면이 "보냈다"고 거짓말을 하고, 호스트는 게스트가 답을 못 받은 이유를 모른다.
 *
 * <p>화면은 이 예외를 보기 전에 입력창 대신 미지원 표시를 그린다. 여기는 최종
 * 방어선이다 — 계획서 6.1 이 말하는 {@code capabilities()} 의 세 번째 사용처다.
 */
public class ChannelMessagingUnsupportedException extends DomainException {

    public ChannelMessagingUnsupportedException(String channelCode) {
        super("CHANNEL_MESSAGING_UNSUPPORTED",
                "이 채널은 메시지 발송을 지원하지 않습니다. channel=" + channelCode);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
