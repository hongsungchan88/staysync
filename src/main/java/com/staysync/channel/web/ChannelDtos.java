package com.staysync.channel.web;

import com.staysync.channel.ChannelConnectionService.MappingBoard;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.ChannelMapping;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.port.Capability;
import com.staysync.property.UnitSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 채널 API 의 요청·응답 본문.
 *
 * <p><b>이 파일의 핵심 규칙은 하나다 — 자격 증명 평문이 응답에 실리지 않는다.</b>
 * {@code credentials} 자리에 들어가는 것은 언제나 마스킹된 값이고, 그 마스킹은
 * {@code ChannelCredentialStore} 가 만든다. 여기서 엔티티의 암호문을 그대로 담거나
 * 복호화하는 코드를 넣지 말 것. 새는 쪽에서는 아무 증상이 없다.
 */
final class ChannelDtos {

    private ChannelDtos() {
    }

    // --- 요청 ----------------------------------------------------------------

    /**
     * 연결 생성.
     *
     * <p>{@code credentials} 는 키·값 그대로 받는다. 어떤 키가 필요한지는 채널마다
     * 다르고(iCal 은 {@code ical_url}, Channex 는 {@code api_key}), 그 해석은 어댑터의
     * 몫이다. 서버가 키 목록을 강제하면 어댑터를 추가할 때마다 이 파일을 고쳐야 한다.
     */
    record CreateConnectionRequest(
            @NotBlank @Size(max = 40) String channelCode,
            @NotNull AdapterType adapterType,
            @Size(max = 100) String displayName,
            Map<String, String> credentials) {
    }

    /** 수정. {@code null} 인 항목은 건드리지 않고, 빈 자격 증명은 기존 값을 유지한다. */
    record UpdateConnectionRequest(
            @Size(max = 100) String displayName,
            Boolean syncEnabled,
            Map<String, String> credentials) {
    }

    record CreateMappingRequest(
            @NotNull Long unitId,
            @NotBlank @Size(max = 120) String externalUnitId,
            @Size(max = 120) String externalRateId) {
    }

    // --- 응답 ----------------------------------------------------------------

    /**
     * 연결 하나.
     *
     * @param credentials 마스킹된 값만. 평문은 어떤 경로로도 나가지 않는다
     * @param capabilities 이 채널이 지원하는 기능. 화면이 "요금 전파 미지원"을 여기서 읽는다
     */
    record ConnectionResponse(
            Long id,
            Long propertyId,
            String channelCode,
            AdapterType adapterType,
            String displayName,
            boolean syncEnabled,
            Map<String, String> credentials,
            Set<Capability> capabilities) {

        static ConnectionResponse of(ChannelConnection connection,
                                     Map<String, String> maskedCredentials,
                                     Set<Capability> capabilities) {
            return new ConnectionResponse(
                    connection.getId(), connection.getPropertyId(), connection.getChannelCode(),
                    connection.getAdapterType(), connection.getDisplayName(),
                    connection.isSyncEnabled(), maskedCredentials, capabilities);
        }
    }

    /**
     * 매핑 화면 한 장.
     *
     * <p>판매 단위마다 매핑을 붙여 내려보낸다. {@code mapping} 이 {@code null} 인 단위는
     * 그 채널에 나가지 않는다. 화면이 그걸 눈에 띄게 표시한다.
     */
    record MappingBoardResponse(ConnectionResponse connection, List<UnitMappingRow> units) {

        static MappingBoardResponse of(MappingBoard board, ConnectionResponse connection) {
            Map<Long, ChannelMapping> byUnit = board.mappings().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            ChannelMapping::getUnitId, mapping -> mapping,
                            // 같은 단위에 매핑이 둘일 수 없다(V3 유니크 인덱스).
                            // 그래도 병합 함수는 있어야 하므로 앞엣것을 남긴다.
                            (first, second) -> first));
            return new MappingBoardResponse(
                    connection,
                    board.units().stream()
                            .map(unit -> UnitMappingRow.of(unit, byUnit.get(unit.id())))
                            .toList());
        }
    }

    record UnitMappingRow(Long unitId, String unitName, MappingResponse mapping) {

        static UnitMappingRow of(UnitSummary unit, ChannelMapping mapping) {
            return new UnitMappingRow(unit.id(), unit.name(),
                    mapping == null ? null : MappingResponse.from(mapping));
        }
    }

    record MappingResponse(Long id, Long unitId, String externalUnitId, String externalRateId) {

        static MappingResponse from(ChannelMapping mapping) {
            return new MappingResponse(mapping.getId(), mapping.getUnitId(),
                    mapping.getExternalUnitId(), mapping.getExternalRateId());
        }
    }
}
