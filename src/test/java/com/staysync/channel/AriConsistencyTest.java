package com.staysync.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.SyncJobStatus;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.support.MockOtaProcess;
import com.staysync.channel.support.SyncTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * <b>P3 완료 조건 2.</b> 계획서 12.2 의 문장 그대로다.
 *
 * <blockquote>5% 에러율을 주입한 상태에서 ARI 100건을 전송해도 재시도 후 최종 정합성이
 * 유지된다.</blockquote>
 *
 * <p>11주차 시뮬레이터를 <b>별도 프로세스로 실제로 띄워</b> 판정한다. 스텁으로 대신하면
 * 검증 대상이 우리 흉내가 된다.
 *
 * <p><b>시드를 고정한다.</b> 재현되지 않는 주입은 실패가 결함 때문인지 운 때문인지
 * 구분되지 않아 아무것도 보증하지 못한다. 시뮬레이터가 그걸 위해 시드를 받는다.
 *
 * <p>여기서 "최종 정합성"은 <b>시뮬레이터가 마지막으로 받은 값이 우리가 마지막으로
 * 만든 값과 같다</b>는 뜻이다. 중간에 몇 번 실패했는지는 상관없다 — 재시도가 그걸
 * 흡수하는 것이 이 정책의 존재 이유다.
 */
class AriConsistencyTest extends SyncTestBase {

    /** 계획서 12.2 가 못박은 수. 줄이면 5% 가 한 번도 안 걸릴 수 있다. */
    private static final int ARI_COUNT = 100;

    private static final LocalDate 첫날 = LocalDate.of(2027, 6, 1);

    @Autowired
    private ObjectMapper json;

    private MockOtaProcess simulator;

    @BeforeEach
    void 시뮬레이터를_띄운다() {
        // SyncTestBase 의 연결이 쓰는 키와 같아야 한다. 다르면 시뮬레이터가 401 로
        // 답하고 어댑터가 그걸 영구 오류로 옮겨 전부 DEAD 가 된다. 11주차에 넣은
        // 자격 증명 검사가 실제로 그렇게 걸린다.
        simulator = MockOtaProcess.start("stub-key", Map.of(
                "error-rate", "0.05",
                // 시드를 고정하면 같은 순번에서 같은 실패가 난다.
                "seed", "20260905"));
    }

    @AfterEach
    void 시뮬레이터를_내린다() {
        if (simulator != null) {
            simulator.close();
        }
    }

    @Test
    @DisplayName("5% 에러율에서 ARI 100건을 보내도 최종 값이 일치한다")
    void 재시도_뒤_최종_정합성이_유지된다() throws Exception {
        Fixture fixture = given("정합성");
        ChannelConnection connection = connect(fixture, "MOCK_CONSISTENCY", AdapterType.MOCK,
                simulator.baseUrl(), "room-1");

        // 날짜마다 다른 요금 100건. 같은 날짜를 반복하면 버퍼가 합쳐 버려 100건이
        // 되지 않는다.
        Map<LocalDate, BigDecimal> 우리_값 = new HashMap<>();
        for (int i = 0; i < ARI_COUNT; i++) {
            LocalDate date = 첫날.plusDays(i);
            BigDecimal rate = BigDecimal.valueOf(200_000 + i * 1_000L);
            우리_값.put(date, rate);
            buffer.enqueue(connection.getId(), "room-1", "rate-1", date,
                    null, rate, 1, null);
            // 하나씩 flush 해 작업 100건을 만든다. 한 번에 flush 하면 연속 날짜가
            // 한 구간으로 압축돼 작업이 하나가 된다 — 그건 버퍼 테스트에서 이미 봤다.
            buffer.flushAll();
        }
        assertThat(worker.jobsOf(connection.getId())).hasSize(ARI_COUNT);

        // 재시도가 끝날 때까지 돌린다. 5% 가 걸린 작업은 백오프로 밀리므로 시간을
        // 앞당겨 다시 집히게 한다. 백오프의 길이를 검증하는 것은 여기가 아니다.
        for (int cycle = 0; cycle < 400; cycle++) {
            jdbc.update("UPDATE sync_job SET next_run_at = now() - interval '1 second' "
                    + "WHERE connection_id = ? AND status = 'PENDING'", connection.getId());
            if (worker.drainOnce() == 0) {
                break;
            }
        }

        // 죽은 작업이 없어야 한다. 8회를 다 쓴 것이 있으면 최종 정합성이 깨진다.
        assertThat(worker.countByStatus(connection.getId(), SyncJobStatus.DEAD))
                .as("5%% 에러율에서 8회를 연달아 실패할 확률은 사실상 0 이다")
                .isZero();
        assertThat(worker.countByStatus(connection.getId(), SyncJobStatus.SUCCESS))
                .isEqualTo(ARI_COUNT);

        // 시뮬레이터가 실제로 받은 값과 대조한다. 우리 로그가 성공이라고 해서 채널이
        // 받았다는 뜻은 아니다 — 그 간극이 이 조건이 겨냥하는 지점이다.
        Map<LocalDate, BigDecimal> 채널_값 = 시뮬레이터가_가진_값();
        assertThat(채널_값)
                .as("채널이 가진 값이 우리 값과 같아야 한다")
                .containsExactlyInAnyOrderEntriesOf(우리_값);
    }

    /**
     * 시뮬레이터가 받은 ARI 를 날짜별 최종 요금으로 접는다.
     *
     * <p>같은 날짜가 여러 번 왔으면 <b>나중에 온 것이 이긴다.</b> 재시도가 만든 중복은
     * 정상이고, 채널이 마지막으로 본 값이 무엇인지가 정합성의 기준이다.
     */
    private Map<LocalDate, BigDecimal> 시뮬레이터가_가진_값() throws Exception {
        Map<LocalDate, BigDecimal> latest = new HashMap<>();
        for (JsonNode entry : json.readTree(simulator.receivedAri())) {
            for (JsonNode segment : entry.get("body").get("segments")) {
                LocalDate from = LocalDate.parse(segment.get("from").asText());
                LocalDate to = LocalDate.parse(segment.get("to").asText());
                BigDecimal rate = segment.get("rate").decimalValue();
                for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                    latest.put(date, rate);
                }
            }
        }
        return latest;
    }
}
