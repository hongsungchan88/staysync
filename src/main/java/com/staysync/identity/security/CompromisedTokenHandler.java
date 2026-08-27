package com.staysync.identity.security;

import com.staysync.identity.RefreshTokenRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유출된 것으로 판단된 토큰을 일괄 무효화한다.
 *
 * <p><b>별도 빈으로 분리한 이유가 이 클래스의 존재 이유다.</b> 재사용 탐지는 무효화한 뒤
 * 갱신 실패 예외를 던진다. 그런데 무효화가 같은 트랜잭션 안에서 일어나면, 뒤이어 던지는
 * 런타임 예외가 그 트랜잭션을 통째로 롤백시켜 무효화까지 없던 일이 된다. 탐지는 로그에만
 * 남고 공격자의 토큰은 그대로 살아 있게 된다.
 *
 * <p>그래서 {@link Propagation#REQUIRES_NEW} 로 별도 트랜잭션에서 처리한다. 바깥
 * 트랜잭션이 롤백돼도 무효화는 이미 커밋되어 남는다.
 *
 * <p>같은 클래스 안에서 {@code @Transactional} 메서드를 부르면 프록시를 거치지 않아
 * 전파 설정이 걸리지 않는다. {@code InventoryService} 와 {@code InventoryLedgerWriter} 를
 * 나눈 것과 같은 이유다.
 */
@Component
class CompromisedTokenHandler {

    private final RefreshTokenRepository repository;

    CompromisedTokenHandler(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int revokeAllOf(Long userId, OffsetDateTime now) {
        return repository.revokeAllOfUser(userId, now);
    }
}
