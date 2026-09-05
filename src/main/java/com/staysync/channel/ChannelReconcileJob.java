package com.staysync.channel;

import com.staysync.booking.DailyAvailability;
import com.staysync.booking.InventoryService;
import com.staysync.channel.domain.ChannelConnection;
import com.staysync.channel.domain.ChannelMapping;
import com.staysync.channel.port.Capability;
import com.staysync.channel.port.ChannelAdapter;
import com.staysync.channel.port.ChannelAriDay;
import com.staysync.channel.port.ChannelCredentials;
import com.staysync.pricing.DayRate;
import com.staysync.pricing.RateCalendarView;
import com.staysync.property.UnitCatalog;
import com.staysync.property.UnitSummary;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정기 재동기화. 계획서 6.6 이 명세다.
 *
 * <p>매일 새벽 4시에 향후 180일을 전수 대조한다. 우리 값과 채널 값이 다르면 병합
 * 버퍼에 다시 넣고 경고를 남긴다.
 *
 * <p><b>이 배치가 12주차가 남긴 두 구멍을 덮는다.</b>
 *
 * <ol>
 *   <li><b>{@code RUNNING} 인 채 앱이 죽어 유실된 작업</b> — 그 전송은 영영 나가지
 *       않는다. {@link SyncJobWorker#reviveOrphans()} 가 몇 분 안에 되살리지만, 그것도
 *       놓친 것은 여기서 수렴한다</li>
 *   <li><b>메모리 버퍼에 있다가 사라진 6초치</b> — {@code AriCoalescingBuffer} 는
 *       메모리에 있어서 앱이 죽으면 아직 flush 되지 않은 창이 사라진다. 6초를 잃는
 *       대신 분당 한도를 지키는 것이 그 설계의 교환이고, 잃은 6초를 메우는 것이
 *       여기다</li>
 * </ol>
 *
 * <p>둘 다 <b>우리 쪽 로그는 전부 성공</b>인 채로 채널에 옛 값이 남는 종류의 결함이다.
 * 그래서 "다시 보낸다"가 아니라 "실제로 다른지 본다"가 이 배치의 일이다.
 *
 * <p><b>15초 사슬 밖이다</b>(작업지시 10 의 4절). 완료 조건 1의 여유가 2초뿐이라
 * 이 배치가 폴링·릴레이·버퍼·워커 어디에도 끼어들면 안 된다. 하루에 한 번 도는
 * 별도 스케줄이고, 결과는 버퍼에 넣어 평소 경로로 흘려보낸다.
 *
 * <p>{@code PUSH_AVAILABILITY} 를 지원하는 연결만 본다. iCal 은 보낼 수가 없어
 * 대조할 것도 없다.
 */
@Component
public class ChannelReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ChannelReconcileJob.class);

    /** 계획서 6.6 의 향후 180일. */
    private static final int HORIZON_DAYS = 180;

    private final ChannelConnectionRepository connections;
    private final ChannelMappingRepository mappings;
    private final ChannelAdapterRegistry registry;
    private final ChannelCredentialStore credentials;
    private final AriCoalescingBuffer buffer;
    private final InventoryService inventory;
    private final RateCalendarView rates;
    private final UnitCatalog unitCatalog;
    private final int horizonDays;

    ChannelReconcileJob(ChannelConnectionRepository connections,
                        ChannelMappingRepository mappings,
                        ChannelAdapterRegistry registry,
                        ChannelCredentialStore credentials,
                        AriCoalescingBuffer buffer,
                        InventoryService inventory,
                        RateCalendarView rates,
                        UnitCatalog unitCatalog,
                        @Value("${staysync.channel.reconcile-horizon-days:180}") int horizonDays) {
        this.connections = connections;
        this.mappings = mappings;
        this.registry = registry;
        this.credentials = credentials;
        this.buffer = buffer;
        this.inventory = inventory;
        this.rates = rates;
        this.unitCatalog = unitCatalog;
        this.horizonDays = horizonDays > 0 ? horizonDays : HORIZON_DAYS;
    }

    /** 계획서 6.6 의 새벽 4시. 사람도 채널도 한가한 시간이다. */
    @Scheduled(cron = "${staysync.channel.reconcile-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        reconcileAll();
    }

    /**
     * 활성 연결을 한 바퀴 돈다. 테스트와 측정이 직접 부른다.
     *
     * <p>한 연결의 실패가 다른 연결을 막지 않는다. 폴링과 같은 이유다 — 여기서 예외를
     * 올리면 뒤의 연결이 그날 대조를 통째로 건너뛴다.
     *
     * @return 다시 보내기로 한 날짜 수. 이 값이 <b>불일치 누적량</b>이고 계획서 6.6 이
     *         측정하라고 한 수치다
     */
    public int reconcileAll() {
        int drift = 0;
        for (ChannelConnection connection : connections.findAll()) {
            if (!connection.isSyncEnabled()) {
                continue;
            }
            if (!registry.capabilitiesOf(connection.getAdapterType())
                    .contains(Capability.PUSH_AVAILABILITY)) {
                continue;
            }
            drift += reconcileOne(connection);
        }
        return drift;
    }

    /** 연결 하나. 실패해도 예외를 올리지 않는다. */
    public int reconcileOne(ChannelConnection connection) {
        try {
            ChannelAdapter adapter = registry.get(connection.getAdapterType());
            ChannelCredentials creds = new ChannelCredentials(
                    connection.getId(), connection.getChannelCode(),
                    credentials.reveal(connection.getCredentials()));
            boolean pushesRate = registry.capabilitiesOf(connection.getAdapterType())
                    .contains(Capability.PUSH_RATE);

            LocalDate from = LocalDate.now();
            LocalDate to = from.plusDays(horizonDays);

            int drift = 0;
            for (ChannelMapping mapping : mappings.findByConnectionIdOrderByIdAsc(
                    connection.getId())) {
                drift += reconcileMapping(connection, adapter, creds, mapping, from, to, pushesRate);
            }
            return drift;
        } catch (RuntimeException e) {
            log.warn("재동기화 대조에 실패했다. 내일 다시 본다. connectionId={} 사유={}",
                    connection.getId(), e.toString());
            return 0;
        }
    }

    private int reconcileMapping(ChannelConnection connection, ChannelAdapter adapter,
                                 ChannelCredentials creds, ChannelMapping mapping,
                                 LocalDate from, LocalDate to, boolean pushesRate) {
        Map<LocalDate, ChannelAriDay> theirs = new HashMap<>();
        for (ChannelAriDay day : adapter.fetchAriSnapshot(
                creds, mapping.getExternalUnitId(), from, to)) {
            theirs.put(day.date(), day);
        }

        UnitSummary unit = unitCatalog.summaryOf(mapping.getUnitId());
        List<DailyAvailability> ours = inventory.availabilityByDate(
                mapping.getUnitId(), from, to, unit.totalUnits());
        Map<LocalDate, DayRate> ourRates = pushesRate
                ? ratesOf(unit, from, to) : Map.of();

        int drift = 0;
        for (DailyAvailability day : ours) {
            ChannelAriDay their = theirs.get(day.date());
            DayRate rate = ourRates.get(day.date());
            if (matches(day, rate, their)) {
                continue;
            }
            // 버퍼를 거친다. 여기서 직접 작업을 만들면 180일이 세그먼트 180개가 되고
            // 분당 한도를 그대로 넘긴다. 버퍼가 연속 구간으로 압축한다.
            buffer.enqueue(connection.getId(), mapping.getExternalUnitId(),
                    mapping.getExternalRateId(), day.date(),
                    day.available(), rate == null ? null : rate.price(),
                    rate == null ? null : (int) rate.minStay(), day.stopSell());
            drift++;
        }

        if (drift > 0) {
            log.warn("[RECONCILE] 채널 값이 우리 값과 달라 다시 보낸다. "
                            + "connectionId={} unitId={} 날짜수={}",
                    connection.getId(), mapping.getUnitId(), drift);
        }
        return drift;
    }

    /**
     * 하루치가 같은지.
     *
     * <p>채널이 그 날짜를 아예 모르면({@code null}) 다르다고 본다 — 우리가 보낸 적이
     * 있는데 채널에 없으면 유실이고, 보낸 적이 없더라도 지금 값을 알려 주는 편이 맞다.
     *
     * <p>반대로 <b>채널이 어떤 항목을 {@code null} 로 돌려주는 것은 "그 값은 모른다"</b>는
     * 뜻이라 건너뛴다. 모르는 것을 다르다고 판정하면 매일 새벽에 전 기간을 다시 보낸다.
     */
    private static boolean matches(DailyAvailability ours, DayRate ourRate, ChannelAriDay theirs) {
        if (theirs == null) {
            return false;
        }
        if (theirs.availability() != null && theirs.availability() != ours.available()) {
            return false;
        }
        if (theirs.stopSell() != null && theirs.stopSell() != ours.stopSell()) {
            return false;
        }
        if (ourRate == null) {
            return true;
        }
        if (theirs.rate() != null && theirs.rate().compareTo(ourRate.price()) != 0) {
            return false;
        }
        return theirs.minStay() == null
                || Objects.equals(theirs.minStay(), (int) ourRate.minStay());
    }

    private Map<LocalDate, DayRate> ratesOf(UnitSummary unit, LocalDate from, LocalDate to) {
        Long ratePlanId = unit.defaultRatePlanId();
        Map<LocalDate, DayRate> index = new HashMap<>();
        if (ratePlanId == null) {
            return index;
        }
        for (DayRate rate : rates.ratesOf(List.of(ratePlanId), from, to)) {
            index.put(rate.date(), rate);
        }
        return index;
    }
}
