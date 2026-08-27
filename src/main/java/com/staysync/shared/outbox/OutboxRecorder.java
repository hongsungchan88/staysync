package com.staysync.shared.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도메인 이벤트를 Outbox 에 적는다. 모듈들이 쓰는 진입점이다.
 *
 * <p><b>부르는 쪽의 트랜잭션에 합류한다.</b> 전파가 기본값 {@code REQUIRED} 인 것이
 * 이 클래스에서 가장 중요한 사실이다. 예약 확정이 롤백되면 "예약이 확정됐다"는 이벤트도
 * 함께 사라져야 한다. 일어나지 않은 일을 바깥에 알릴 수는 없다.
 *
 * <p>이 프로젝트에서 트랜잭션 경계 문제가 세 번째로 나오는 자리다. 판별 기준은 같다 —
 * <b>바깥이 롤백될 때 이 쓰기가 남아야 하는가, 사라져야 하는가.</b> ADR 0005 의
 * {@code REQUIRES_NEW} 는 "실패로 끝나면서 기록은 남겨야" 하는 반대 경우였다.
 * 여기에 그걸 가져오면 유령 이벤트가 생긴다.
 *
 * <p>{@code shared} 에 있으므로 도메인 타입을 알지 못한다. 애그리게이트 종류와 이벤트
 * 종류를 문자열로 받는 이유다. 여기서 {@code booking} 을 참조하면 의존이 거꾸로 흐른다.
 */
@Component
public class OutboxRecorder {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    OutboxRecorder(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 이벤트를 적는다.
     *
     * <p>{@code payload} 에는 <b>식별자만</b> 담아야 한다. 이름과 연락처는 넣지 않는다.
     * 게스트 연락처를 암호화해 두고 페이로드에 평문으로 실으면 데이터베이스 덤프 하나로
     * 둘 다 새면서 한쪽만 잠근 셈이 된다. 소비자가 값이 필요하면 식별자로 조회해
     * 복호화한다. 근거는 ADR 0007 결과 절.
     *
     * @param aggregateType 애그리게이트 종류. 예: {@code RESERVATION}
     * @param aggregateId   애그리게이트 식별자
     * @param eventType     이벤트 종류. 예: {@code RESERVATION_CONFIRMED}
     * @param payload       식별자 위주의 값. JSON 으로 직렬화되어 저장된다
     */
    @Transactional
    public OutboxEvent record(String aggregateType, Long aggregateId, String eventType,
                              Map<String, Object> payload) {
        return repository.save(new OutboxEvent(
                aggregateType, aggregateId, eventType, toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            // 페이로드를 만들지 못하면 도메인 변경까지 되돌린다. 이벤트 없는 변경을
            // 남기면 바깥이 영영 모르는 상태가 되기 때문이다.
            throw new IllegalStateException("이벤트 페이로드를 직렬화하지 못했습니다.", e);
        }
    }
}
