package com.staysync.channel;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 같은 연결 안에서 한 판매 단위를 두 번 매핑했다.
 *
 * <p>막지 않으면 같은 재고를 채널에 두 번 보내게 되고, 그 증상은 12주차 동기화 워커가
 * 돌기 시작해야 나온다. 그때는 원인이 매핑에 있다는 것을 알아내기 어렵다.
 */
public class DuplicateChannelMappingException extends DomainException {

    public DuplicateChannelMappingException(Long unitId) {
        super("DUPLICATE_CHANNEL_MAPPING",
                "이 연결에 이미 매핑된 판매 단위입니다. unitId=" + unitId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
