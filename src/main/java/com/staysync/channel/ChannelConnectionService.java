package com.staysync.channel;

import com.staysync.channel.adapter.channex.ChannexAdapter;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.ChannelMapping;
import com.staysync.channel.domain.SyncJob;
import com.staysync.channel.domain.SyncJobStatus;
import com.staysync.channel.port.AdapterType;
import com.staysync.property.OwnedResources;
import com.staysync.property.UnitCatalog;
import com.staysync.property.UnitNotFoundException;
import com.staysync.property.UnitSummary;
import com.staysync.shared.audit.AuditRecorder;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널 연결과 매핑의 변경 지점.
 *
 * <p>모든 진입점이 {@link OwnedResources} 를 거친다. 경로의 식별자는 "무엇을 원하는가"
 * 일 뿐 "그것을 볼 수 있는가"의 근거가 아니다.
 *
 * <p>판매 단위는 {@link UnitCatalog} 로만 받는다. {@code property.domain.Unit} 은
 * property 모듈의 내부 구현이라 참조하면 {@code ModularityTest} 가 깨진다.
 * 의존 방향은 channel → property 다.
 *
 * <p><b>연결의 생성·수정·삭제는 감사에 남는다.</b> 남의 계정 자격 증명을 건드리는
 * 일이라서다. 전후 값에는 자격 증명을 담지 않고 어떤 필드가 바뀌었는지만 남긴다
 * (ADR 0007 결과 절, 5~6주차와 같은 선).
 */
@Service
@Transactional(readOnly = true)
public class ChannelConnectionService {

    private static final String ENTITY = "CHANNEL_CONNECTION";

    /** 감사 기록에서 자격 증명 변경을 나타내는 표시. 값도 키 이름도 담지 않는다. */
    private static final String CREDENTIALS_CHANGED = "변경됨";

    /**
     * 발행 토큰의 바이트 수. 16진수로 64자가 되어 컬럼 길이와 맞는다.
     *
     * <p>URL 자체가 인증이므로 추측 가능하면 남의 예약 일정이 새어 나간다.
     * 리프레시 토큰과 같은 폭이다.
     */
    private static final int EXPORT_TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChannelConnectionRepository connections;
    private final ChannelMappingRepository mappings;
    private final SyncJobRepository syncJobs;
    private final ChannelCredentialStore credentialStore;
    private final OwnedResources owned;
    private final UnitCatalog unitCatalog;
    private final AuditRecorder audit;

    ChannelConnectionService(ChannelConnectionRepository connections,
                             ChannelMappingRepository mappings,
                             SyncJobRepository syncJobs,
                             ChannelCredentialStore credentialStore,
                             OwnedResources owned,
                             UnitCatalog unitCatalog,
                             AuditRecorder audit) {
        this.connections = connections;
        this.mappings = mappings;
        this.syncJobs = syncJobs;
        this.credentialStore = credentialStore;
        this.owned = owned;
        this.unitCatalog = unitCatalog;
        this.audit = audit;
    }

    // --- 연결 ---------------------------------------------------------------

    public List<ChannelConnection> listOf(Long orgId) {
        List<Long> propertyIds = owned.propertyIdsOf(orgId);
        return propertyIds.isEmpty()
                ? List.of()
                : connections.findByPropertyIdInOrderByIdAsc(propertyIds);
    }

    public ChannelConnection get(Long connectionId, Long orgId) {
        ChannelConnection connection = connections.findById(connectionId)
                .orElseThrow(() -> new ChannelConnectionNotFoundException(connectionId));
        if (!owned.ownsProperty(connection.getPropertyId(), orgId)) {
            throw new ChannelConnectionNotFoundException(connectionId);
        }
        return connection;
    }

    @Transactional
    public ChannelConnection create(Long propertyId, Long orgId, String channelCode,
                                    AdapterType adapterType, String displayName,
                                    Map<String, String> credentials) {
        if (!owned.ownsProperty(propertyId, orgId)) {
            // 남의 숙소에 연결을 만들려는 요청이다. 없다고 답한다.
            throw new ChannelConnectionNotFoundException(null);
        }
        // iCal 은 내보내기 주소 하나가 판매 단위 하나라 숙소당 연결이 여럿이다(V8).
        // 다른 어댑터는 숙소 단위로 붙으므로 그대로 하나만 둔다.
        if (adapterType != AdapterType.ICAL
                && connections.existsByPropertyIdAndChannelCode(propertyId, channelCode)) {
            throw new DuplicateChannelConnectionException(channelCode);
        }
        if (adapterType == AdapterType.CHANNEX) {
            // 둘 다 없으면 어댑터가 첫 호출에서야 실패하고 그 실패는 워커 로그에만 남는다.
            requireCredential(credentials, ChannexAdapter.API_KEY);
            requireCredential(credentials, ChannexAdapter.PROPERTY_ID);
        }

        ChannelConnection saved = connections.save(new ChannelConnection(
                propertyId, channelCode, adapterType, displayName,
                credentialStore.seal(credentials)));

        audit.record(ENTITY, saved.getId(), "CHANNEL_CONNECT", null, Map.of(
                "propertyId", propertyId,
                "channelCode", channelCode,
                "adapterType", adapterType.name(),
                "credentials", CREDENTIALS_CHANGED));
        return saved;
    }

    /**
     * 표시 이름, 활성 여부, 자격 증명을 고친다.
     *
     * <p>{@code null} 인 항목은 건드리지 않는다. 자격 증명은 빈 값이면 기존 값을
     * 유지한다 — 화면이 저장된 값을 다시 받지 못하기 때문이다.
     */
    @Transactional
    public ChannelConnection update(Long connectionId, Long orgId, String displayName,
                                    Boolean syncEnabled, Map<String, String> credentials) {
        ChannelConnection connection = get(connectionId, orgId);

        Map<String, Object> before = new HashMap<>();
        Map<String, Object> after = new HashMap<>();

        if (displayName != null && !displayName.equals(connection.getDisplayName())) {
            before.put("displayName", connection.getDisplayName());
            after.put("displayName", displayName);
            connection.rename(displayName);
        }
        if (syncEnabled != null && syncEnabled != connection.isSyncEnabled()) {
            before.put("syncEnabled", connection.isSyncEnabled());
            after.put("syncEnabled", syncEnabled);
            connection.changeSyncEnabled(syncEnabled);
        }

        String merged = credentialStore.merge(connection.getCredentials(), credentials);
        if (!Objects.equals(merged, connection.getCredentials())) {
            // 전후 값에 자격 증명을 담지 않는다. 바뀌었다는 사실만 남긴다.
            after.put("credentials", CREDENTIALS_CHANGED);
            connection.replaceCredentials(merged);
        }

        if (!after.isEmpty()) {
            audit.record(ENTITY, connectionId, "CHANNEL_UPDATE", before, after);
        }
        return connection;
    }

    @Transactional
    public void delete(Long connectionId, Long orgId) {
        ChannelConnection connection = get(connectionId, orgId);
        // 매핑은 FK 의 ON DELETE CASCADE 로 함께 사라진다.
        connections.delete(connection);
        audit.record(ENTITY, connectionId, "CHANNEL_DISCONNECT", Map.of(
                "propertyId", connection.getPropertyId(),
                "channelCode", connection.getChannelCode()), null);
    }

    /**
     * 이 연결에서 사람이 봐야 할 실패. DEAD 는 재시도가 고치지 못하는 것이라(틀린 매핑, 채널이
     * 거부한 값) 화면에 안 보이면 조용히 실패하는 자리가 된다(작업지시-17 8.3).
     */
    public SyncFailure failureOf(Long connectionId) {
        long dead = syncJobs.countByConnectionIdAndStatus(connectionId, SyncJobStatus.DEAD);
        String lastError = dead == 0 ? null : syncJobs
                .findFirstByConnectionIdAndStatusOrderByIdDesc(connectionId, SyncJobStatus.DEAD)
                .map(SyncJob::getLastError).orElse(null);
        return new SyncFailure(dead, lastError);
    }

    public record SyncFailure(long deadJobs, String lastError) {
    }

    // --- 매핑 ---------------------------------------------------------------

    /**
     * 매핑 화면이 한 번에 필요로 하는 것.
     *
     * <p>판매 단위 전부와 그중 매핑된 것을 함께 넘긴다. <b>매핑되지 않은 단위는 그
     * 채널에 나가지 않으므로</b> 화면이 그걸 보여 줘야 하고, 매핑 목록만으로는 부족하다.
     */
    public MappingBoard mappingBoard(Long connectionId, Long orgId) {
        ChannelConnection connection = get(connectionId, orgId);
        return new MappingBoard(
                connection,
                unitCatalog.summariesOf(connection.getPropertyId()),
                mappings.findByConnectionIdOrderByIdAsc(connectionId));
    }

    public record MappingBoard(ChannelConnection connection,
                               List<UnitSummary> units,
                               List<ChannelMapping> mappings) {
    }

    @Transactional
    public ChannelMapping addMapping(Long connectionId, Long orgId, Long unitId,
                                     String externalUnitId, String externalRateId) {
        ChannelConnection connection = get(connectionId, orgId);
        requireUnitOfConnection(connection, unitId);

        // 유니크 인덱스(V3)가 최종 방어선이고, 이 확인은 사용자에게 이유를 알려 주기
        // 위한 것이다. 확인만 두면 동시 요청이 통과하고, 인덱스만 두면 500 이 나간다.
        if (mappings.existsByConnectionIdAndUnitId(connectionId, unitId)) {
            throw new DuplicateChannelMappingException(unitId);
        }
        if (connection.getAdapterType() == AdapterType.CHANNEX
                && (externalRateId == null || externalRateId.isBlank())) {
            // Channex 는 요금제가 ARI 의 최소 단위다. 방만 매핑하면 채널이 켜지지 않는다.
            throw new MissingChannelFieldException("요금제 식별자(externalRateId)");
        }
        rejectDoubleIntake(connection, unitId);
        return mappings.save(new ChannelMapping(connectionId, unitId, externalUnitId,
                externalRateId, newExportToken()));
    }

    /**
     * 같은 판매 단위에 iCal 과 Channex 를 함께 매핑하지 못하게 한다(작업지시-17 D).
     *
     * <p>에어비앤비가 Channex 를 거치면 같은 예약이 iCal 발행물로도 온다. 채널 코드가
     * 달라 {@code uq_channel_booking} 이 못 막고 초과 판매 충돌로 뜬다(조사-04 5절 3번).
     * Mock 은 시뮬레이터라 어느 쪽과도 겹치지 않는다. 먼저 조회해 분기한다 — 유니크
     * 제약으로 잡을 수 있는 모양이 아니다(연결이 다르다).
     */
    private void rejectDoubleIntake(ChannelConnection connection, Long unitId) {
        AdapterType mine = connection.getAdapterType();
        if (mine != AdapterType.ICAL && mine != AdapterType.CHANNEX) {
            return;
        }
        AdapterType other = mine == AdapterType.ICAL ? AdapterType.CHANNEX : AdapterType.ICAL;
        for (ChannelMapping existing : mappings.findByUnitId(unitId)) {
            boolean clashes = connections.findById(existing.getConnectionId())
                    .map(c -> c.getAdapterType() == other)
                    .orElse(false);
            if (clashes) {
                throw new DoubleIntakeMappingException(unitId, other);
            }
        }
    }

    private static void requireCredential(Map<String, String> credentials, String key) {
        String value = credentials == null ? null : credentials.get(key);
        if (value == null || value.isBlank()) {
            throw new MissingChannelFieldException(key);
        }
    }

    /**
     * 발행 URL 의 토큰. <b>이 경로에서만 원문이 나간다.</b>
     *
     * <p>목록·상세 응답에는 담지 않는다. 화면이 매핑을 그릴 때마다 토큰이 실리면
     * 브라우저 캐시와 로그 어디에나 남고, 그건 새는 쪽에서 아무 증상이 없다.
     * 호스트가 에어비앤비 2단계에 붙여 넣을 때만 꺼낸다(조사-02 1절).
     *
     * <p>채널 종류를 가리지 않고 만든다. iCal 을 가져갈 수 있는 채널이 에어비앤비만은
     * 아니고, 종류로 갈라 두면 나중에 그 분기가 조용히 어긋난다.
     */
    public String exportTokenOf(Long connectionId, Long orgId, Long mappingId) {
        get(connectionId, orgId);
        return mappings.findById(mappingId)
                .filter(mapping -> mapping.getConnectionId().equals(connectionId))
                .map(ChannelMapping::getExportToken)
                .orElseThrow(() -> new ChannelConnectionNotFoundException(connectionId));
    }

    private static String newExportToken() {
        byte[] bytes = new byte[EXPORT_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @Transactional
    public void deleteMapping(Long connectionId, Long orgId, Long mappingId) {
        get(connectionId, orgId);
        mappings.findById(mappingId)
                .filter(mapping -> mapping.getConnectionId().equals(connectionId))
                .ifPresent(mappings::delete);
    }

    /**
     * 그 판매 단위가 이 연결의 숙소에 속하는지.
     *
     * <p>{@code OwnedResources.unit} 을 쓰지 않는다. 그 메서드는
     * {@code property.domain.Unit} 을 돌려주는데 그건 property 의 내부 타입이라
     * channel 이 참조하면 {@code ModularityTest} 가 깨진다. 연결의 숙소는 이미 소유
     * 확인을 거쳤으므로, 그 숙소의 판매 단위 목록에 있는지만 보면 된다.
     */
    private void requireUnitOfConnection(ChannelConnection connection, Long unitId) {
        boolean belongs = unitCatalog.summariesOf(connection.getPropertyId()).stream()
                .anyMatch(unit -> unit.id().equals(unitId));
        if (!belongs) {
            throw new UnitNotFoundException(unitId);
        }
    }
}
