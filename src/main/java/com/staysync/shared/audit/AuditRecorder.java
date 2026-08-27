package com.staysync.shared.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.staysync.shared.security.AuthenticatedUser;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 기록을 남긴다. 모듈들이 쓰는 진입점이다.
 *
 * <p>{@link com.staysync.shared.outbox.OutboxRecorder} 와 마찬가지로 <b>부르는 쪽의
 * 트랜잭션에 합류한다.</b> 변경이 롤백되면 기록도 함께 사라져야 한다.
 *
 * <p>주체는 {@code SecurityContext} 에서 알아서 가져온다. 부르는 쪽이 매번 넘기게 하면
 * 어딘가에서 빠뜨리고, 빠뜨린 기록은 나중에 누가 했는지 알 수 없는 행으로 남는다.
 * <b>배치에는 {@code SecurityContext} 가 없으므로 자동으로 {@code SYSTEM} 이 된다.</b>
 */
@Component
public class AuditRecorder {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    AuditRecorder(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 현재 주체로 기록한다.
     *
     * <p>인증된 요청이면 {@code USER} 와 사용자 식별자를, 배치면 {@code SYSTEM} 과
     * 빈 식별자를 쓴다.
     *
     * <p>{@code before} 와 {@code after} 에는 <b>바뀐 필드만</b> 담는다. 연락처 값은
     * 넣지 않는다. 필요하면 식별자로 조회해 복호화한다(ADR 0007).
     */
    @Transactional
    public AuditLog record(String entityType, Long entityId, String action,
                           Map<String, Object> before, Map<String, Object> after) {
        Optional<AuthenticatedUser> current = AuthenticatedUser.current();
        return repository.save(new AuditLog(
                current.map(AuthenticatedUser::userId).orElse(null),
                current.isPresent() ? ActorKind.USER : ActorKind.SYSTEM,
                entityType, entityId, action,
                toJson(before), toJson(after)));
    }

    /**
     * 주체를 지정해 기록한다. 채널 수신(P3)처럼 {@code SecurityContext} 로는 표현할 수
     * 없는 주체를 위한 것이다.
     */
    @Transactional
    public AuditLog recordAs(ActorKind actorKind, Long actorId,
                             String entityType, Long entityId, String action,
                             Map<String, Object> before, Map<String, Object> after) {
        return repository.save(new AuditLog(
                actorId, actorKind, entityType, entityId, action, toJson(before), toJson(after)));
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 기록을 만들지 못하면 변경까지 되돌린다. 감사 없는 변경을 남기는 것보다
            // 변경을 실패시키는 편이 낫다.
            throw new IllegalStateException("감사 기록을 직렬화하지 못했습니다.", e);
        }
    }
}
