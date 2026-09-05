package com.staysync.channel;

import com.staysync.channel.port.AdapterType;
import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 요청한 종류의 어댑터가 레지스트리에 없다.
 *
 * <p>{@code null} 을 돌려주지 않는 이유다. 조용히 비어 있는 값을 넘기면 호출부마다
 * 방어 코드가 생기고, 빠뜨린 한 곳에서 {@code NullPointerException} 이 난다.
 * 어댑터가 없는 것은 설정이 아니라 아직 구현하지 않은 상태이므로 500 이다.
 */
public class AdapterNotRegisteredException extends DomainException {

    public AdapterNotRegisteredException(AdapterType type) {
        super("ADAPTER_NOT_REGISTERED", "등록된 채널 어댑터가 없습니다. type=" + type);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
