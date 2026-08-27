package com.staysync.shared.security;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 인증된 요청의 주체. 모듈들이 "지금 요청한 사람이 누구인가"를 읽는 유일한 통로다.
 *
 * <p>identity 가 토큰을 검증해 이 값을 {@code SecurityContext} 에 채우고, property 같은
 * 다른 모듈의 웹 계층이 꺼내 쓴다. 타입이 shared 에 있으므로 읽는 쪽은 identity 를
 * 참조하지 않는다. 의존은 {@code identity → shared}, {@code property → shared} 두 갈래로
 * 갈릴 뿐 두 모듈 사이에는 간선이 생기지 않는다.
 *
 * <p>{@code orgId} 가 특히 중요하다. 조회를 조직 단위로 좁히는 근거가 되며, 이 값을
 * 거치지 않고 요청 본문이나 경로의 조직 식별자를 믿으면 다른 조직의 데이터가 노출된다.
 *
 * @param userId {@code user_account.id}
 * @param orgId  소속 조직. 모든 데이터 접근의 스코프
 * @param role   역할
 */
public record AuthenticatedUser(Long userId, Long orgId, Role role) {

    public AuthenticatedUser {
        if (userId == null || orgId == null || role == null) {
            throw new IllegalArgumentException("인증 주체의 식별자와 역할은 비어 있을 수 없습니다.");
        }
    }

    /**
     * 현재 요청의 주체를 꺼낸다.
     *
     * <p>인증되지 않은 요청에서는 비어 있다. 보호된 엔드포인트라면 필터가 이미 401 로
     * 막았을 것이므로, 값이 없는 경우를 컨트롤러가 따로 처리할 일은 없다.
     */
    public static Optional<AuthenticatedUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public boolean isOwner() {
        return role == Role.OWNER;
    }

    /** 숙소나 예약 같은 자원이 이 주체의 조직에 속하는지. */
    public boolean canAccessOrg(Long targetOrgId) {
        return orgId.equals(targetOrgId);
    }
}
