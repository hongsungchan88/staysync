package com.staysync.channel;

import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.SyncJob;
import com.staysync.channel.domain.SyncJobType;
import com.staysync.channel.port.Capability;
import com.staysync.channel.port.ChannelAdapter;
import com.staysync.channel.port.ChannelCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * {@link ChannelMessageGateway} 의 구현. messaging 이 채널에 닿는 유일한 자리다.
 *
 * <p>자격 증명을 복호화하는 것은 여기까지다. messaging 은 연결 식별자만 들고 있고
 * 실제 키는 보지 못한다 — 복호화하는 자리를 {@code ChannelCredentialStore} 하나로
 * 좁혀 둔 규칙이 모듈을 넘어서도 유지된다(ADR 0007).
 */
@Service
class ChannelMessageGatewayService implements ChannelMessageGateway {

    private static final Logger log = LoggerFactory.getLogger(ChannelMessageGatewayService.class);

    private final ChannelConnectionRepository connections;
    private final ChannelAdapterRegistry registry;
    private final ChannelCredentialStore credentials;
    private final SyncJobRepository jobs;
    private final ObjectMapper json;

    ChannelMessageGatewayService(ChannelConnectionRepository connections,
                                 ChannelAdapterRegistry registry,
                                 ChannelCredentialStore credentials,
                                 SyncJobRepository jobs,
                                 ObjectMapper json) {
        this.connections = connections;
        this.registry = registry;
        this.credentials = credentials;
        this.jobs = jobs;
        this.json = json;
    }

    @Override
    public List<ChannelMessagingConnection> messagingConnections() {
        List<ChannelMessagingConnection> result = new ArrayList<>();
        for (ChannelConnection connection : connections.findAll()) {
            if (connection.isSyncEnabled() && supports(connection)) {
                result.add(new ChannelMessagingConnection(
                        connection.getId(), connection.getPropertyId(), connection.getChannelCode()));
            }
        }
        return result;
    }

    @Override
    public boolean supportsMessaging(Long connectionId) {
        return connections.findById(connectionId).map(this::supports).orElse(false);
    }

    @Override
    public List<InboundChannelMessage> pull(Long connectionId) {
        ChannelConnection connection = connections.findById(connectionId).orElse(null);
        if (connection == null || !supports(connection)) {
            return List.of();
        }
        try {
            ChannelAdapter adapter = registry.get(connection.getAdapterType());
            return adapter.pullMessages(credentialsOf(connection));
        } catch (RuntimeException e) {
            // 한 채널의 실패가 다른 채널의 수집을 막지 않는다. 폴링과 같은 판단이다.
            log.warn("채널 메시지 수집에 실패했다. 다음 주기에 다시 읽는다. connectionId={} 사유={}",
                    connectionId, e.toString());
            return List.of();
        }
    }

    /**
     * 발송 작업을 만든다.
     *
     * <p><b>{@code MESSAGING} 을 지원하지 않는 연결에는 작업을 만들지 않는다.</b>
     * 만들면 워커가 어댑터의 {@code sendMessage} 를 부르고 거기서
     * {@code UnsupportedOperationException} 이 나 8번 재시도한 끝에 {@code DEAD} 가
     * 된다. 보낼 수 없는 채널이라는 사실은 작업을 만들기 전에 안다.
     */
    @Override
    public boolean enqueueSend(Long connectionId, OutboundChannelMessage message,
                               String idempotencyKey) {
        ChannelConnection connection = connections.findById(connectionId)
                .orElseThrow(() -> new ChannelConnectionNotFoundException(connectionId));
        if (!supports(connection)) {
            log.debug("메시징을 지원하지 않는 연결이라 발송 작업을 만들지 않는다. connectionId={} adapter={}",
                    connectionId, connection.getAdapterType());
            return false;
        }
        if (jobs.existsOutstanding(connectionId, idempotencyKey)) {
            // 같은 발송이 이미 대기 중이다. 중복 이벤트는 오류가 아니라 정상 동작이다.
            return false;
        }
        try {
            jobs.save(new SyncJob(connectionId, SyncJobType.SEND_MESSAGE,
                    serialize(message), idempotencyKey));
            return true;
        } catch (DataIntegrityViolationException e) {
            // uq_syncjob_pending 이 막았다. 확인과 저장 사이에 다른 요청이 끼어든 것이고,
            // 막고 싶었던 것이 정확히 그것이므로 오류로 올리지 않는다.
            log.debug("같은 발송이 이미 대기 중이라 넘긴다. connectionId={}", connectionId);
            return false;
        }
    }

    private boolean supports(ChannelConnection connection) {
        return registry.capabilitiesOf(connection.getAdapterType()).contains(Capability.MESSAGING);
    }

    private ChannelCredentials credentialsOf(ChannelConnection connection) {
        return new ChannelCredentials(connection.getId(), connection.getChannelCode(),
                credentials.reveal(connection.getCredentials()));
    }

    private String serialize(OutboundChannelMessage message) {
        try {
            return json.writeValueAsString(message);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("메시지를 직렬화하지 못했습니다.", e);
        }
    }
}
