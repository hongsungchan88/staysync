package com.staysync.channel;

import com.staysync.booking.DailyAvailability;
import com.staysync.booking.InventoryService;
import com.staysync.channel.adapter.ical.IcalWriter;
import com.staysync.channel.domain.ChannelMapping;
import com.staysync.property.UnitCatalog;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 우리 캘린더를 iCal 발행물로 만든다. 계획서 6.2 의 발행 쪽이다.
 *
 * <p><b>어댑터를 거치지 않는다.</b> 발행은 우리가 채널을 부르는 일이 아니라 채널이
 * 우리를 읽어 가는 일이라, {@code ChannelAdapter} 에 자리가 없다. 13주차에
 * {@code exportCalendar} 기본 구현을 지운 이유다.
 *
 * <p><b>"막혔다"의 기준은 재고다.</b> 확정 예약 목록을 따로 읽지 않고
 * {@link InventoryService#availabilityByDate} 가 돌려주는 날짜별 판매 가능 수량을
 * 본다. 예약이 두 건 있어도 아직 팔 수 있는 방이 남았으면 막지 않는 것이 옳고,
 * 판매중지도 같은 값에 이미 들어 있다. 예약 목록으로 만들면 {@code totalUnits} 가
 * 둘 이상인 판매 단위에서 <b>팔 수 있는 방을 못 팔게 된다.</b>
 *
 * <p>이 규칙 덕분에 {@code DTEND} 배타성이 저절로 지켜진다. 예약이 차지하는 것은
 * 체크인부터 체크아웃 <i>전날</i>까지의 밤이므로, 연속 구간의 끝 다음 날이 곧
 * 체크아웃일이고 그 값이 그대로 {@code DTEND} 가 된다.
 */
@Service
public class IcalExportService {

    /** 발행물의 캐시 수명. 계획서 6.2 의 5분이다. */
    public static final long CACHE_SECONDS = 300;

    private final ChannelMappingRepository mappings;
    private final ChannelConnectionRepository connections;
    private final InventoryService inventory;
    private final UnitCatalog unitCatalog;
    private final int horizonDays;

    IcalExportService(ChannelMappingRepository mappings,
                      ChannelConnectionRepository connections,
                      InventoryService inventory,
                      UnitCatalog unitCatalog,
                      @Value("${staysync.channel.ical.export-horizon-days:365}") int horizonDays) {
        this.mappings = mappings;
        this.connections = connections;
        this.inventory = inventory;
        this.unitCatalog = unitCatalog;
        this.horizonDays = horizonDays;
    }

    /**
     * 토큰으로 발행물을 만든다.
     *
     * <p>토큰이 없거나 연결이 꺼져 있으면 비어 온다. 부르는 쪽은 <b>둘을 구분하지 않고
     * 404</b> 로 답한다 — 구분해 주면 대입으로 유효한 토큰을 좁힐 수 있다.
     */
    public Optional<String> exportByToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return mappings.findByExportToken(token)
                .filter(mapping -> connections.findById(mapping.getConnectionId())
                        .map(connection -> connection.isSyncEnabled())
                        .orElse(false))
                .map(this::export);
    }

    private String export(ChannelMapping mapping) {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(horizonDays);
        short totalUnits = unitCatalog.totalUnitsOf(mapping.getUnitId());

        List<IcalWriter.BlockedRange> ranges = blockedRanges(
                inventory.availabilityByDate(mapping.getUnitId(), from, to, totalUnits));

        // UID 의 도메인 자리에 토큰을 넣지 않는다. 발행물 자체가 URL 을 흘리게 된다.
        return IcalWriter.write(ranges, "unit-" + mapping.getUnitId() + ".staysync");
    }

    /**
     * 팔 수 없는 날을 연속 구간으로 묶는다.
     *
     * <p>구간의 {@code endExclusive} 는 마지막으로 막힌 날의 <b>다음 날</b>이다.
     * 3박 예약이면 막힌 밤이 셋이고 그 다음 날이 체크아웃일이므로 값이 맞아떨어진다.
     */
    private static List<IcalWriter.BlockedRange> blockedRanges(List<DailyAvailability> days) {
        List<IcalWriter.BlockedRange> ranges = new ArrayList<>();
        LocalDate runStart = null;
        LocalDate runLast = null;

        for (DailyAvailability day : days) {
            boolean blocked = day.stopSell() || day.available() <= 0;
            if (!blocked) {
                if (runStart != null) {
                    ranges.add(new IcalWriter.BlockedRange(runStart, runLast.plusDays(1)));
                    runStart = null;
                }
                continue;
            }
            if (runStart == null) {
                runStart = day.date();
            } else if (!day.date().equals(runLast.plusDays(1))) {
                // 날짜가 끊겼다. 앞 구간을 닫고 새로 연다.
                ranges.add(new IcalWriter.BlockedRange(runStart, runLast.plusDays(1)));
                runStart = day.date();
            }
            runLast = day.date();
        }
        if (runStart != null) {
            ranges.add(new IcalWriter.BlockedRange(runStart, runLast.plusDays(1)));
        }
        return ranges;
    }
}
