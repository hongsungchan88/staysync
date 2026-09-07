package com.staysync.channel;

import com.staysync.booking.ChannelBookingCommand;
import com.staysync.booking.ChannelBookingIntake;
import com.staysync.booking.ChannelBookingResult;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.ChannelMapping;
import com.staysync.channel.port.AdapterType;
import com.staysync.channel.port.BookingFeed;
import com.staysync.channel.port.Capability;
import com.staysync.channel.port.ChannelAdapter;
import com.staysync.channel.port.ChannelCredentials;
import com.staysync.channel.port.InboundBooking;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 일이라 미뤄 두었고, 13주차에도 그대로다(작업지시 10 의 3절).
 *
 * <p><b>{@code sync_job} 을 만들지 않는다.</b> 작업 큐는 "보낸 것이 실패하면 다시
 * 보낸다"를 위한 장치인데, 폴링은 실패해도 다음 주기에 같은 목록을 다시 읽으므로
 * 재시도가 이미 들어 있다. 작업으로 만들면 같은 폴링이 큐에도 쌓이고 주기로도 돌아
 * 두 번 읽는다.
 *
 * <p><b>채널의 실패가 다른 채널을 막지 않는다.</b> 한 연결에서 예외가 나면 로그를
 * 남기고 다음 연결로 넘어간다. 여기서 예외를 올리면 뒤의 연결이 그 주기를 통째로
 * 건너뛴다.
 *
 * <p>P3 13주차에 iCal 이 붙으면서 셋이 더해졌다.
 *
 * <ol>
 *   <li>{@code ETag} 조건부 요청 — 값은 연결에 저장한다</li>
 *   <li><b>대량 소실 방어</b> — 이벤트 수가 직전 대비 절반 이하로 줄면 그 주기를 버린다</li>
 *   <li>{@link Capability#SNAPSHOT_BOOKING} — 발행물에서 사라진 예약을 취소한다</li>
 * </ol>
 */
@Component
public class ChannelBookingPoller {

    private static final Logger log = LoggerFactory.getLogger(ChannelBookingPoller.class);

    /**
     * 대량 소실 방어가 걸리기 시작하는 이벤트 수. 계획서 13.4 의 값이다.
     *
     * <p>이보다 적으면 방어하지 않는다. 한두 건짜리 발행물은 정상적으로 0 이 될 수
     * 있어서 방어를 걸면 <b>정상적인 취소가 영영 반영되지 않는다.</b> 실제
     * 에어비앤비 미게시 리스팅의 발행물이 {@code VEVENT} 한 건뿐이라(조사-02 2절)
     * 여기에 해당한다 — 그 연결에는 방어가 걸리지 않고, 그것이 의도된 값이다.
     */
    private static final int SHRINK_GUARD_MIN = 4;

    private final ChannelConnectionRepository connections;
    private final ChannelMappingRepository mappings;
    private final ChannelAdapterRegistry registry;
    private final ChannelCredentialStore credentials;
    private final ChannelBookingIntake intake;
    private final boolean enabled;

    /** 구현 없는 채널을 기동당 한 번만 알리기 위한 표시. */
    private final Set<AdapterType> warnedMissingAdapters =
            Collections.synchronizedSet(EnumSet.noneOf(AdapterType.class));

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
     *
     * <p><b>iCal 은 이 주기를 따르지 않는다.</b> 계획서 6.2 의 15분이 따로 있고,
     * 그래서 iCal 은 15초 사슬 밖이다.
     */
    @Scheduled(fixedDelayString = "${staysync.channel.poll-interval-ms:5000}",
            initialDelayString = "${staysync.channel.poll-interval-ms:5000}")
    public void run() {
        if (enabled) {
            pollAll();
        }
    }

    /**
     * iCal 폴링. 계획서 6.2 의 15분 주기다.
     *
     * <p>다른 채널과 주기를 나눈 이유는 둘뿐이다 — iCal 발행물은 최대 2시간 지연되므로
     * 5초마다 읽어 봐야 같은 것을 다시 받고, 그 왕복이 15초 사슬 안의 다른 채널
     * 폴링을 밀어낸다.
     */
    @Scheduled(fixedDelayString = "${staysync.channel.ical-poll-interval-ms:900000}",
            initialDelayString = "${staysync.channel.ical-poll-interval-ms:900000}")
    public void runIcal() {
        if (enabled) {
            pollAll(true);
        }
    }

    /** 모든 활성 연결을 한 바퀴 돈다. 테스트가 스케줄러를 기다리지 않고 부른다. */
    public int pollAll() {
        return pollAll(false);
    }

    /**
     * @param snapshotOnly 참이면 스냅샷 채널(iCal)만, 거짓이면 나머지만 돈다.
     *                     주기가 달라서 갈린다
     */
    public int pollAll(boolean snapshotOnly) {
        int ingested = 0;
        for (ChannelConnection connection : connections.findAll()) {
            if (!connection.isSyncEnabled()) {
                continue;
            }
            if (!registry.isRegistered(connection.getAdapterType())) {
                // 고르는 조건과 실행하는 조건이 어긋나던 자리다. 아래 capabilities 는
                // AdapterType 의 선언이라 구현이 없어도 PULL_BOOKING 을 돌려주고,
                // 그러면 매 주기 이 연결을 집어 AdapterNotRegisteredException 으로
                // 실패한다. 확인-05 3절 C 의 connectionId=2 가 그것이었다.
                warnOnceAboutMissingAdapter(connection);
                continue;
            }
            Set<Capability> capabilities = registry.capabilitiesOf(connection.getAdapterType());
            if (!capabilities.contains(Capability.PULL_BOOKING)) {
                continue;
            }
            if (capabilities.contains(Capability.SNAPSHOT_BOOKING) != snapshotOnly) {
                continue;
            }
            ingested += pollOne(connection);
        }
        return ingested;
    }

    /**
     * 구현 없는 채널을 <b>기동당 한 번만</b> 알린다.
     *
     * <p>조용히 넘기면 그 연결은 영영 동기화되지 않는데 로그에도 아무것도 남지 않는다 —
     * 12주차 {@code MOCK} 결함의 모양이다. 그렇다고 주기마다 남기면 5초에 한 줄씩
     * 쌓여 진짜 경고를 덮는다. 종류당 한 번이면 둘 다 피한다.
     */
    private void warnOnceAboutMissingAdapter(ChannelConnection connection) {
        if (warnedMissingAdapters.add(connection.getAdapterType())) {
            log.warn("등록된 어댑터가 없는 채널이라 예약을 수집하지 않는다. "
                            + "connectionId={} type={} — 어댑터가 붙기 전까지 이 연결은 동기화되지 않는다",
                    connection.getId(), connection.getAdapterType());
        }
    }

    /** 연결 하나. 실패해도 예외를 올리지 않는다 — 다음 연결이 이 주기를 잃지 않게. */
    public int pollOne(ChannelConnection connection) {
        try {
            ChannelAdapter adapter = registry.get(connection.getAdapterType());
            ChannelCredentials creds = new ChannelCredentials(
                    connection.getId(), connection.getChannelCode(),
                    credentials.reveal(connection.getCredentials()));

            BookingFeed feed = adapter.pullBookings(creds, connection.getEtag());
            if (feed.unchanged()) {
                // 304 다. 파싱도 하지 않았고 판단할 근거도 없다. 여기서 빈 목록으로
                // 다루면 스냅샷 채널이 그 연결의 예약을 전부 취소한다.
                log.debug("발행물이 바뀌지 않았다. connectionId={}", connection.getId());
                return 0;
            }

            boolean snapshot = registry.capabilitiesOf(connection.getAdapterType())
                    .contains(Capability.SNAPSHOT_BOOKING);
            if (snapshot && shrankSuspiciously(connection, feed.bookings().size())) {
                // 기준값도 ETag 도 갱신하지 않는다. 갱신하면 줄어든 수가 다음 주기의
                // 기준이 되어, 한 번 더 줄어들 때는 방어가 걸리지 않는다.
                return 0;
            }

            Map<String, ChannelMapping> byExternalId = mappingsOf(connection);
            int ingested = 0;
            Set<String> seen = new LinkedHashSet<>();
            for (InboundBooking booking : feed.bookings()) {
                ChannelMapping mapping = resolveMapping(connection, byExternalId, booking);
                if (mapping == null) {
                    continue;
                }
                seen.add(booking.bookingId());
                if (ingest(connection, mapping, booking)) {
                    ingested++;
                }
            }

            if (snapshot) {
                cancelMissing(connection, byExternalId.values(), seen);
            }
            connection.recordFeed(feed.etag(), feed.bookings().size());
            connections.save(connection);
            return ingested;
        } catch (RuntimeException e) {
            log.warn("채널 예약 수집에 실패했다. 다음 주기에 다시 읽는다. connectionId={} 사유={}",
                    connection.getId(), e.toString());
            return 0;
        }
    }

    /**
     * 이번 발행물이 직전 대비 절반 이하로 줄었는지. 계획서 13.4 의 대량 소실 방어다.
     *
     * <p>파싱이 어긋나거나 발행자가 잠깐 빈 달력을 내면 <b>그 채널의 예약이 전부
     * 취소된다.</b> 그 사고는 되돌릴 수 없다 — 취소가 다시 채널로 전파되고, 그때는
     * 우리 쪽 로그도 전부 정상이다. 한 주기를 버리는 대가는 15분이다.
     *
     * <p>알림 체계가 아직 없어 {@code ERROR} 로그로 남긴다. 계획서 14.4 의 지표는
     * P6 이다.
     */
    private static boolean shrankSuspiciously(ChannelConnection connection, int incoming) {
        Integer previous = connection.getLastEventCount();
        if (previous == null || previous <= SHRINK_GUARD_MIN) {
            return false;
        }
        if (incoming * 2 > previous) {
            return false;
        }
        log.error("발행물의 일정 수가 절반 이하로 줄어 이번 주기를 버린다. "
                        + "connectionId={} 직전={} 이번={} — 취소를 반영하지 않았다",
                connection.getId(), previous, incoming);
        return true;
    }

    /**
     * 예약이 어느 판매 단위의 것인지 찾는다.
     *
     * <p>발행물에 상품 식별자가 있는 채널(Mock, Channex)은 그 값으로 찾는다.
     * <b>iCal 에는 그런 값이 없다</b> — {@code VEVENT} 는 날짜와 {@code UID} 뿐이다.
     * iCal 은 내보내기 URL 하나가 리스팅 하나이므로(조사-02 1절) 그 연결의 매핑이
     * 곧 답이고, 매핑이 둘 이상이면 어느 쪽인지 알 방법이 없어 넘긴다.
     */
    private ChannelMapping resolveMapping(ChannelConnection connection,
                                          Map<String, ChannelMapping> byExternalId,
                                          InboundBooking booking) {
        if (booking.externalUnitId() != null) {
            ChannelMapping mapping = byExternalId.get(booking.externalUnitId());
            if (mapping == null) {
                // 매핑되지 않은 객실의 예약이다. 우리 어느 판매 단위에 넣을지 알 수 없다.
                // 조용히 버리면 예약이 사라지므로 남긴다 — 매핑을 고치면 다음 주기에 들어온다.
                log.warn("매핑되지 않은 채널 객실의 예약이라 넣지 못한다. "
                                + "connectionId={} externalUnitId={} bookingId={}",
                        connection.getId(), booking.externalUnitId(), booking.bookingId());
            }
            return mapping;
        }
        if (byExternalId.size() != 1) {
            log.warn("발행물에 객실 식별자가 없는데 이 연결의 매핑이 {} 개다. "
                            + "iCal 은 내보내기 URL 하나가 리스팅 하나여야 한다. connectionId={}",
                    byExternalId.size(), connection.getId());
            return null;
        }
        return byExternalId.values().iterator().next();
    }

    private boolean ingest(ChannelConnection connection, ChannelMapping mapping,
                           InboundBooking booking) {
        ChannelBookingResult result = intake.ingest(new ChannelBookingCommand(
                connection.getPropertyId(), mapping.getUnitId(),
                connection.getChannelCode(), booking.bookingId(),
                // 여기서 떨어지고 있었다. InboundBooking 에는 처음부터 있던 값이고,
                // 빠진 탓에 채널 예약에 게스트가 붙지 않아 {{guestName}} 템플릿이
                // 나가지 못했다(작업지시 12 의 5절 1번).
                booking.guestName(),
                booking.checkIn(), booking.checkOut(),
                booking.totalAmount(), booking.revision(), booking.isCancellation()));

        if (result.outcome() == ChannelBookingResult.Outcome.CONFLICT) {
            log.warn("재고를 넘겨 받아들였다. reservationId={} 날짜={} 운영자가 해소해야 한다",
                    result.reservationId(), result.conflictDates());
        }
        return result.outcome() != ChannelBookingResult.Outcome.DUPLICATE;
    }

    /**
     * 발행물에서 사라진 예약을 취소한다.
     *
     * <p>매핑된 판매 단위마다 부른다. 매핑이 없는 연결은 애초에 예약을 받지도
     * 못하므로 취소할 것도 없다.
     */
    private void cancelMissing(ChannelConnection connection,
                               java.util.Collection<ChannelMapping> connectionMappings,
                               Set<String> seen) {
        Set<Long> unitIds = new HashSet<>();
        for (ChannelMapping mapping : connectionMappings) {
            if (!unitIds.add(mapping.getUnitId())) {
                continue;
            }
            int cancelled = intake.cancelMissing(
                    mapping.getUnitId(), connection.getChannelCode(), seen);
            if (cancelled > 0) {
                log.info("발행물에서 사라진 예약 {} 건을 취소했다. connectionId={} unitId={}",
                        cancelled, connection.getId(), mapping.getUnitId());
            }
        }
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
