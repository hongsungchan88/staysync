package com.mockota;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 받은 ARI 를 그대로 쌓아 둔다.
 *
 * <p><b>해석하지 않는다.</b> 요금이 말이 되는지, 날짜가 맞는지 보지 않는다. 우리가
 * 무엇을 보냈는지 되돌려 보여 주는 것이 역할의 전부다. 검증하기 시작하면 12주차가
 * 검증하는 대상이 우리 어댑터가 아니라 시뮬레이터의 해석이 된다.
 *
 * <p>본문을 {@link JsonNode} 로 담는 이유도 같다. 타입을 정해 받으면 어댑터가 보낸 것
 * 가운데 타입에 없는 필드가 조용히 사라지고, 그러면 "보낸 그대로"가 거짓이 된다.
 *
 * <p>상태는 메모리에만 있다. 재기동하면 비고, 그래도 된다(ADR 0011).
 */
@Component
public class AriStore {

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    /** @param receivedAt 순서 확인용. 12주차의 병합 버퍼가 몇 번에 나눠 보냈는지 본다 */
    public record Entry(long seq, Instant receivedAt, JsonNode body) {
    }

    public Entry append(JsonNode body) {
        Entry entry = new Entry(sequence.incrementAndGet(), Instant.now(), body);
        entries.add(entry);
        return entry;
    }

    public List<Entry> all() {
        return List.copyOf(entries);
    }

    /**
     * 전부 버린다.
     *
     * <p>12주차의 "전송한 ARI 무시"(재동기화 배치 검증)가 이 경로로 동작한다. 채널이
     * 받은 것을 잃어버린 상황을 만들고, 재동기화가 그걸 다시 채우는지 본다.
     */
    public void clear() {
        entries.clear();
    }
}
