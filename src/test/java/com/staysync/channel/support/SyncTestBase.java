package com.staysync.channel.support;

import com.staysync.booking.InventoryService;
import com.staysync.channel.AriCoalescingBuffer;
import com.staysync.channel.ChannelConnectionService;
import com.staysync.channel.SyncJobWorker;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.port.AdapterType;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import com.staysync.shared.outbox.OutboxRelay;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 동기화 엔진 테스트의 공통 설정과 픽스처.
 *
 * <p>포트와 데이터 디렉터리를 {@code GuestEncryptionTest} 계열과 같게 두어 스프링
 * 컨텍스트를 공유한다. 속성이 하나라도 다르면 캐시 키가 갈려 내장 PostgreSQL 이 하나 더
 * 뜨고, 같은 데이터 디렉터리를 두고 부딪힌다.
 *
 * <p>주기 실행은 {@code build.gradle} 의 test 태스크가 전부 멈춰 뒀다. 테스트는
 * {@link AriCoalescingBuffer#flushAll()}, {@link SyncJobWorker#drainOnce()},
 * {@link OutboxRelay#relayPending()} 을 직접 부른다 — 스케줄러와 같은 대상을 두고
 * 경쟁하면 "나중 값 하나만 나간다" 같은 검증이 타이밍에 따라 갈린다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
public abstract class SyncTestBase {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected UnitRegistrationService unitRegistration;

    @Autowired
    protected ChannelConnectionService channels;

    @Autowired
    protected AriCoalescingBuffer buffer;

    @Autowired
    protected SyncJobWorker worker;

    @Autowired
    protected OutboxRelay relay;

    @Autowired
    protected InventoryService inventory;

    /**
     * 밀린 이벤트를 <b>다 나갈 때까지</b> 배수한다.
     *
     * <p>{@code relayPending()} 한 번은 {@code OutboxRelay.BATCH_LIMIT} 만큼만 낸다.
     * 앞선 테스트가 그보다 많은 이벤트를 남겨 두면 <b>이 테스트의 이벤트가 그 뒤에
     * 줄을 서서 한 번의 호출로는 나가지 않는다.</b> HOLD 생성 이벤트가 붙은 P4 16주차에
     * 실제로 그렇게 깨졌다 — 홀드 상한 테스트가 만드는 101건이 뒤 테스트의 한 건을
     * 밀어냈다. 한 번만 부르는 코드는 그 앞에 무엇이 쌓였는지에 기대게 된다.
     */
    protected int drainRelay() {
        int published = 0;
        for (int guard = 0; guard < 50; guard++) {
            int once = relay.relayPending();
            published += once;
            if (once == 0) {
                return published;
            }
        }
        throw new IllegalStateException("릴레이가 비지 않는다. 발행이 수렴하지 않는 것이다.");
    }

    /** 조직·숙소·판매 단위 한 벌. */
    protected record Fixture(Long orgId, Long propertyId, Long unitId) {
    }

    protected Fixture given(String name, short totalUnits) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES (?) RETURNING id", Long.class, name + " 조직");
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        Long unitId = unitRegistration.register(
                propertyId, name + " 객실", UnitKind.ENTIRE_PLACE, totalUnits,
                BigDecimal.valueOf(100_000));
        return new Fixture(orgId, propertyId, unitId);
    }

    protected Fixture given(String name) {
        return given(name, (short) 1);
    }

    /**
     * 매핑까지 붙은 연결 하나.
     *
     * <p>{@link ChannelConnectionService} 를 그대로 거친다. 자격 증명 암호화를 우회해
     * 행을 직접 넣으면 워커가 복호화하는 경로가 테스트에서 한 번도 돌지 않는다.
     */
    protected ChannelConnection connect(Fixture fixture, String channelCode, AdapterType type,
                                        String baseUrl, String externalUnitId) {
        ChannelConnection connection = channels.create(
                fixture.propertyId(), fixture.orgId(), channelCode, type, channelCode,
                Map.of("api_key", "stub-key", "base_url", baseUrl));
        channels.addMapping(connection.getId(), fixture.orgId(), fixture.unitId(),
                externalUnitId, "rate-1");
        return connection;
    }

    /** 매핑이 없는 연결. */
    protected ChannelConnection connectWithoutMapping(Fixture fixture, String channelCode,
                                                      AdapterType type, String baseUrl) {
        return channels.create(fixture.propertyId(), fixture.orgId(), channelCode, type, channelCode,
                Map.of("api_key", "stub-key", "base_url", baseUrl));
    }
}
