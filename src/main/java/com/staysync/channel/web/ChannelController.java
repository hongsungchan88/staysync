package com.staysync.channel.web;

import com.staysync.channel.ChannelAdapterRegistry;
import com.staysync.channel.ChannelConnectionService;
import com.staysync.channel.ChannelCredentialStore;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.web.ChannelDtos.*;
import com.staysync.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 채널 연결과 매핑 API.
 *
 * <p>계획서 8.1 의 {@code /channels} 와 {@code /channels/:id/mapping} 이 쓴다.
 * {@code /channels/:id/logs} 는 동기화 워커가 생긴 뒤(12주차)다.
 *
 * <p>조회와 변경은 전부 {@link ChannelConnectionService} 를 거쳐 조직으로 좁혀진다.
 * 소유가 아니면 404 다.
 */
@RestController
@RequestMapping("/api")
class ChannelController {

    private final ChannelConnectionService service;
    private final ChannelCredentialStore credentialStore;
    private final ChannelAdapterRegistry registry;

    ChannelController(ChannelConnectionService service,
                      ChannelCredentialStore credentialStore,
                      ChannelAdapterRegistry registry) {
        this.service = service;
        this.credentialStore = credentialStore;
        this.registry = registry;
    }

    // --- 연결 ---------------------------------------------------------------

    @GetMapping("/channels")
    List<ConnectionResponse> list() {
        return service.listOf(orgId()).stream().map(this::toResponse).toList();
    }

    @PostMapping("/properties/{propertyId}/channels")
    ResponseEntity<ConnectionResponse> create(@PathVariable Long propertyId,
                                              @Valid @RequestBody CreateConnectionRequest request) {
        ChannelConnection created = service.create(
                propertyId, orgId(), request.channelCode(), request.adapterType(),
                request.displayName(), request.credentials());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping("/channels/{connectionId}")
    ConnectionResponse get(@PathVariable Long connectionId) {
        return toResponse(service.get(connectionId, orgId()));
    }

    @PatchMapping("/channels/{connectionId}")
    ConnectionResponse update(@PathVariable Long connectionId,
                              @Valid @RequestBody UpdateConnectionRequest request) {
        return toResponse(service.update(connectionId, orgId(), request.displayName(),
                request.syncEnabled(), request.credentials()));
    }

    @DeleteMapping("/channels/{connectionId}")
    ResponseEntity<Void> delete(@PathVariable Long connectionId) {
        service.delete(connectionId, orgId());
        return ResponseEntity.noContent().build();
    }

    // --- 매핑 ---------------------------------------------------------------

    @GetMapping("/channels/{connectionId}/mappings")
    MappingBoardResponse mappings(@PathVariable Long connectionId) {
        ChannelConnectionService.MappingBoard board = service.mappingBoard(connectionId, orgId());
        return MappingBoardResponse.of(board, toResponse(board.connection()));
    }

    @PostMapping("/channels/{connectionId}/mappings")
    ResponseEntity<MappingResponse> addMapping(@PathVariable Long connectionId,
                                               @Valid @RequestBody CreateMappingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(MappingResponse.from(
                service.addMapping(connectionId, orgId(), request.unitId(),
                        request.externalUnitId(), request.externalRateId())));
    }

    @DeleteMapping("/channels/{connectionId}/mappings/{mappingId}")
    ResponseEntity<Void> deleteMapping(@PathVariable Long connectionId, @PathVariable Long mappingId) {
        service.deleteMapping(connectionId, orgId(), mappingId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 응답을 만드는 유일한 자리.
     *
     * <p>자격 증명은 반드시 {@link ChannelCredentialStore#masked} 를 거친다. 엔티티의
     * {@code getCredentials()} 를 응답에 담는 코드가 이 클래스 어디에도 없어야 한다.
     */
    private ConnectionResponse toResponse(ChannelConnection connection) {
        return ConnectionResponse.of(
                connection,
                credentialStore.masked(connection.getCredentials()),
                registry.capabilitiesOf(connection.getAdapterType()));
    }

    private static Long orgId() {
        return AuthenticatedUser.current()
                .orElseThrow(() -> new IllegalStateException(
                        "인증이 필요한 경로인데 주체가 없다. SecurityConfig 설정을 확인할 것."))
                .orgId();
    }
}
