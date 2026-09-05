package com.staysync.channel;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/** 한 숙소에 같은 채널 코드의 연결이 이미 있다. {@code uq_channel_conn} 과 짝이다. */
public class DuplicateChannelConnectionException extends DomainException {

    public DuplicateChannelConnectionException(String channelCode) {
        super("DUPLICATE_CHANNEL_CONNECTION",
                "이 숙소에 이미 있는 채널입니다. channelCode=" + channelCode);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
