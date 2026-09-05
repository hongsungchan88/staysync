package com.staysync.channel;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 채널 연결이 없거나 이 조직의 것이 아니다.
 *
 * <p>둘을 구분하지 않는다. 403 으로 존재를 알려 주면 식별자를 훑어 다른 조직의 연결이
 * 있는지 알아낼 수 있다. {@code OwnedResources} 가 세운 규칙과 같다.
 */
public class ChannelConnectionNotFoundException extends DomainException {

    public ChannelConnectionNotFoundException(Long connectionId) {
        super("CHANNEL_CONNECTION_NOT_FOUND", "채널 연결을 찾을 수 없습니다. id=" + connectionId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
