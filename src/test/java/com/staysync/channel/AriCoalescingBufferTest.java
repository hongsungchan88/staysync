package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.SyncJob;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.SyncTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 완료 조건 3·4. ARI 병합 버퍼.
 *
 * <p>Channex 제한이 숙소당 분당 20회다(조사-01). 캘린더에서 30일을 드래그해 요금을
 * 바꾸면 셀 30개가 바뀌는데, 셀마다 보내면 한 번의 조작이 한도를 넘긴다. 6초 윈도로
 * 모으고 연속된 같은 값을 구간으로 압축하는 것이 그걸 막는다.
 */
class AriCoalescingBufferTest extends SyncTestBase {

    private static final LocalDate 첫날 = LocalDate.of(2027, 3, 1);

    @Test
    @DisplayName("6초 안에 같은 날짜를 두 번 바꾸면 나중 값 하나만 나간다")
    void 나중_값이_이긴다() {
        ChannelConnection connection = mockConnection("버퍼-나중값");

        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날,
                null, BigDecimal.valueOf(250_000), 1, null);
        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날,
                null, BigDecimal.valueOf(230_000), 1, null);
        buffer.flushAll();

        // 둘 다 보내면 채널이 25만원을 잠깐 갖게 되고, 그 사이에 들어온 예약은 틀린
        // 요금으로 성사된다. 되돌릴 수 없는 종류의 오차다.
        List<SyncJob> jobs = worker.jobsOf(connection.getId());
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getPayload())
                .contains("230000")
                .doesNotContain("250000");
    }

    @Test
    @DisplayName("재고와 요금이 같은 날짜에 겹쳐도 서로를 지우지 않는다")
    void 다른_항목은_합쳐진다() {
        ChannelConnection connection = mockConnection("버퍼-겹침");

        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날,
                null, BigDecimal.valueOf(250_000), 2, null);
        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날,
                0, null, null, true);
        buffer.flushAll();

        // null 은 "안 바꿈"이지 "지움"이 아니다. 하나로 뭉치면 재고만 바뀐 날의 요금이
        // null 로 나가 채널이 요금을 지운다.
        // JSONB 컬럼이라 돌아올 때 공백과 키 순서가 다시 매겨진다. 값만 본다.
        String payload = worker.jobsOf(connection.getId()).get(0).getPayload().replace(" ", "");
        assertThat(payload)
                .contains("\"rate\":250000")
                .contains("\"minStay\":2")
                .contains("\"availability\":0")
                .contains("\"stopSell\":true");
    }

    @Test
    @DisplayName("연속된 날짜의 같은 값이 하나의 구간으로 압축된다")
    void 연속된_같은_값은_한_구간이_된다() {
        ChannelConnection connection = mockConnection("버퍼-압축");

        for (int i = 0; i < 30; i++) {
            buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날.plusDays(i),
                    1, BigDecimal.valueOf(250_000), 1, false);
        }
        buffer.flushAll();

        // 압축하지 않으면 30일이 세그먼트 30개다. 6개월치가 호출 한 번으로 끝나는 것이
        // 목표다(Channex 자가 인증 항목 8).
        String payload = worker.jobsOf(connection.getId()).get(0).getPayload();
        assertThat(countSegments(payload)).isEqualTo(1);
        assertThat(payload).contains("2027-03-01").contains("2027-03-30");
    }

    @Test
    @DisplayName("값이 다른 날은 구간이 갈린다")
    void 값이_바뀌면_구간이_갈린다() {
        ChannelConnection connection = mockConnection("버퍼-분할");

        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날,
                1, BigDecimal.valueOf(250_000), 1, false);
        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날.plusDays(1),
                1, BigDecimal.valueOf(300_000), 1, false);
        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날.plusDays(2),
                1, BigDecimal.valueOf(250_000), 1, false);
        buffer.flushAll();

        assertThat(countSegments(worker.jobsOf(connection.getId()).get(0).getPayload()))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("날짜가 끊기면 값이 같아도 구간이 갈린다")
    void 날짜가_끊기면_구간이_갈린다() {
        ChannelConnection connection = mockConnection("버퍼-불연속");

        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날,
                1, BigDecimal.valueOf(250_000), 1, false);
        // 하루 건너뛴다. 주말만 편집하면 실제로 이런 모양이 된다.
        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날.plusDays(2),
                1, BigDecimal.valueOf(250_000), 1, false);
        buffer.flushAll();

        // 이어 붙이면 건너뛴 날까지 값이 바뀐다. 바꾸지 않은 날을 바꾸는 것이라
        // 압축보다 훨씬 나쁘다.
        assertThat(countSegments(worker.jobsOf(connection.getId()).get(0).getPayload()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("윈도가 지나지 않으면 flush 되지 않는다")
    void 윈도_전에는_나가지_않는다() {
        ChannelConnection connection = mockConnection("버퍼-윈도");

        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날,
                1, BigDecimal.valueOf(250_000), 1, false);

        // 지금 시각으로는 6초가 지나지 않았다. 모으는 것이 버퍼의 존재 이유이므로
        // 곧바로 나가면 안 된다.
        assertThat(buffer.flushDue(System.currentTimeMillis())).isZero();
        assertThat(worker.jobsOf(connection.getId())).isEmpty();

        // 6초가 지난 것으로 보면 나간다.
        assertThat(buffer.flushDue(System.currentTimeMillis() + 7_000)).isEqualTo(1);
        assertThat(worker.jobsOf(connection.getId())).hasSize(1);
    }

    @Test
    @DisplayName("같은 변경이 두 번 들어와도 대기 중인 작업은 하나다")
    void 같은_변경은_한_번만_대기한다() {
        ChannelConnection connection = mockConnection("버퍼-멱등");

        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날,
                1, BigDecimal.valueOf(250_000), 1, false);
        buffer.flushAll();
        // Outbox 가 최소 1회 전달이라 같은 이벤트가 두 번 올 수 있다. 윈도를 넘겨 오면
        // 버퍼가 합치지 못하므로 멱등성 키가 막는다.
        buffer.enqueue(connection.getId(), "room-1", "rate-1", 첫날,
                1, BigDecimal.valueOf(250_000), 1, false);
        buffer.flushAll();

        assertThat(worker.jobsOf(connection.getId())).hasSize(1);
    }

    private ChannelConnection mockConnection(String name) {
        return connect(given(name), "MOCK_" + name, AdapterType.MOCK,
                "http://localhost:1", "room-1");
    }

    private static int countSegments(String payload) {
        return payload.split("\"from\"", -1).length - 1;
    }
}
