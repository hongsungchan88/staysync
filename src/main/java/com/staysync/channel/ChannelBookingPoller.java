package com.staysync.channel;

import com.staysync.booking.ChannelBookingCommand;
import com.staysync.booking.ChannelBookingIntake;
import com.staysync.booking.ChannelBookingResult;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.ChannelMapping;
import com.staysync.channel.port.Capability;
import com.staysync.channel.port.ChannelAdapter;
import com.staysync.channel.port.ChannelCredentials;
import com.staysync.channel.port.InboundBooking;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 채널에서 예약을 긁어 온다. 계획서 6.5 수신 경로의 (a)(c) 자리다.
 *
 * <p><b>폴링으로 한다</b>(작업지시 09 의 5절 1번). 완료 조건이 15초이고 폴링으로
 * 충족된다. 웹훅 수신은 엔드포인트 하나가 아니라 서명 검증과 인증 표면이 함께 붙는
 * 일이라, 13주차에 iCal 과 나란히 놓고 {@code verifySignature()} 를 실제로 채우면서
 * 보는 편이 낫다. 13주차의 iCal 수신도 폴링이라 코드 경로를 나눠 쓴다.
 *
 * <p><b>{@code sync_job} 을 만들지 않는다.</b> 작업 큐는 "보낸 것이 실패하면 다시
 * 보낸다"를 위한 장치인데, 폴링은 실패해도 다음 주기에 같은 목록을 다시 읽으므로
 * 재시도가 이미 들어 있다. 작업으로 만들면 같은 폴링이 큐에도 쌓이고 주기로도 돌아
 * 두 번 읽는다.
 *
 * <p><b>채널의 실패가 다른 채널을 막지 않는다.</b> 한 연결에서 예외가 나면 로그를
 * 남기고 다음 연결로 넘어간다. 여기서 예외를 올리면 뒤의 연결이 그 주기를 통째로
 * 건너뛴다.
 */
@Component
public class ChannelBookingPoller {

    private static final Logger log = LoggerFactory.getLogger(ChannelBookingPoller.class);

    private final ChannelConnectionRepository connections;
    private final ChannelMappingRepository mappings;
    private final ChannelAdapterRegistry registry;
    private final ChannelCredentialStore credentials;
    private final ChannelBookingIntake intake;
    private final boolean enabled;

    ChannelBookingPoller(ChannelConnectionRepository connections,
                         ChannelMappingRepository mappings,
                         ChannelAdapterRegistry registry,
                         ChannelCredentialStore credentials,
                         ChannelBookingIntake intake,
                         @Value("${staysync.channel.poll-enabled:true}") boolean enabled) {
        this.connections = connections;
        this.mappings = mappings;
        this.registry = registry;
        this.credentials = credentials;
        this.intake = intake;
        this.enabled = enabled;
    }

    /**
     * 주기 실행.
     *
     * <p>기본 5초다. 완료 조건이 "15초 이내"이고, 폴링 주기에 어댑터 왕복과 캘린더
     * 갱신까지 더해도 여유가 있어야 한다. 15초로 두면 최악의 경우가 곧 상한이 된다.
     */
    @Scheduled(fixedDelayString = "${staysync.channel.poll-interval-ms:5000}",
            initialDelayString = "${staysync.channel.poll-interval-ms:5000}")
    public void run() {
        if (enabled) {
            pollAll();
        }
    }

    /** 모든 활성 연결을 한 바퀴 돈다. 테스트가 스케줄러를 기다리지 않고 부른다. */
    public int pollAll() {
        int ingested = 0;
        for (ChannelConnection connection : connections.findAll()) {
            if (!connection.isSyncEnabled()) {
                continue;
            }
            if (!registry.capabilitiesOf(connection.getAdapterType())
                    .contains(Capability.PULL_BOOKING)) {
                continue;
            }
            ingested += pollOne(connection);
        }
        return ingested;
    }

    /** 연결 하나. 실패해도 예외를 올리지 않는다 — 다음 연결이 이 주기를 잃지 않게. */
    public int pollOne(ChannelConnection connection) {
        try {
            ChannelAdapter adapter = registry.get(connection.getAdapterType());
            ChannelCredentials creds = new ChannelCredentials(
                    connection.getId(), connection.getChannelCode(),
                    credentials.reveal(connection.getCredentials()));

            Map<String, ChannelMapping> byExternalId = mappingsOf(connection);
            int ingested = 0;
            for (InboundBooking booking : adapter.pullBookings(creds)) {
                if (ingest(connection, byExternalId, booking)) {
                    ingested++;
                }
            }
            return ingested;
        } catch (RuntimeException e) {
            log.warn("채널 예약 수집에 실패했다. 다음 주기에 다시 읽는다. connectionId={} 사유={}",
                    connection.getId(), e.toString());
            return 0;
        }
    }

    private boolean ingest(ChannelConnection connection, Map<String, ChannelMapping> byExternalId,
                           InboundBooking booking) {
        ChannelMapping mapping = byExternalId.get(booking.externalUnitId());
        if (mapping == null) {
            // 매핑되지 않은 객실의 예약이다. 우리 어느 판매 단위에 넣을지 알 수 없다.
            // 조용히 버리면 예약이 사라지므로 남긴다 — 매핑을 고치면 다음 주기에 들어온다.
            log.warn("매핑되지 않은 채널 객실의 예약이라 넣지 못한다. "
                            + "connectionId={} externalUnitId={} bookingId={}",
                    connection.getId(), booking.externalUnitId(), booking.bookingId());
            return false;
        }
        if (booking.isBlock()) {
            // 예약이 아니라 단순 차단이다. 13주차의 iCal 이 이걸 보낸다.
            return false;
        }

        ChannelBookingResult result = intake.ingest(new ChannelBookingCommand(
                connection.getPropertyId(), mapping.getUnitId(),
                connection.getChannelCode(), booking.bookingId(),
                booking.checkIn(), booking.checkOut(),
                booking.totalAmount(), booking.revision(), booking.isCancellation()));

        if (result.outcome() == ChannelBookingResult.Outcome.CONFLICT) {
            log.warn("재고를 넘겨 받아들였다. reservationId={} 날짜={} 운영자가 해소해야 한다",
                    result.reservationId(), result.conflictDates());
        }
        return result.outcome() != ChannelBookingResult.Outcome.DUPLICATE;
    }

    private Map<String, ChannelMapping> mappingsOf(ChannelConnection connection) {
        Map<String, ChannelMapping> byExternalId = new HashMap<>();
        List<ChannelMapping> found = mappings.findByConnectionIdOrderByIdAsc(connection.getId());
        for (ChannelMapping mapping : found) {
            byExternalId.put(mapping.getExternalUnitId(), mapping);
        }
        return byExternalId;
    }
}
