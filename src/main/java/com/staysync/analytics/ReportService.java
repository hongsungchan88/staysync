package com.staysync.analytics;

import com.staysync.analytics.ReportMetrics.ChannelShare;
import com.staysync.booking.BookingStatistics;
import com.staysync.property.OwnedResources;
import com.staysync.property.UnitCatalog;
import com.staysync.property.UnitSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영 리포트. 계획서 8.8 의 지표 여섯을 구한다.
 *
 * <p><b>집계는 booking 이 한다.</b> {@link BookingStatistics} 가 행이 아니라 합계를
 * 돌려주고, 여기서는 나누기만 한다. 예약 테이블을 SQL 로 직접 읽으면
 * {@code ModularityTest} 는 통과하지만 — 그것은 타입 참조만 본다 — 빌드가 잡아 주지
 * 않는 경계 위반이 되고, 예약 스키마를 고칠 때 이 모듈이 조용히 깨진다.
 *
 * <p><b>0으로 나누는 규칙이 여기 한 곳에 있다.</b> 분자와 분모를 받아 여기서만
 * 나누므로, 그 처리가 두 모듈에 흩어지지 않는다.
 *
 * <p>모든 진입점이 {@link OwnedResources} 를 거친다. 남의 조직 숙소는 빈 결과다.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    /** 비율의 소수 자리. 화면이 백분율로 바꿔 한 자리까지 보여 준다. */
    private static final int RATE_SCALE = 4;

    private final BookingStatistics statistics;
    private final OwnedResources owned;
    private final UnitCatalog unitCatalog;

    ReportService(BookingStatistics statistics, OwnedResources owned, UnitCatalog unitCatalog) {
        this.statistics = statistics;
        this.owned = owned;
        this.unitCatalog = unitCatalog;
    }

    /**
     * 기간과 숙소로 좁힌 지표.
     *
     * @param propertyId {@code null} 이면 조직의 숙소 전부
     * @param from       포함
     * @param to         포함. 이 날짜의 박까지 센다
     */
    public ReportMetrics of(Long orgId, Long propertyId, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new InvalidReportRangeException(from, to);
        }
        List<Long> propertyIds = targetProperties(orgId, propertyId);
        if (propertyIds.isEmpty()) {
            // 숙소가 없거나 남의 것이다. 빈 결과가 맞다 — 없다고 알려 주지 않는다.
            return empty();
        }

        long availableNights = availableNights(propertyIds, from, to);
        BookingStatistics.SoldNights sold = statistics.soldNights(propertyIds, from, to);
        BookingStatistics.CancellationCounts cancels =
                statistics.cancellationCounts(propertyIds, from, to);

        return new ReportMetrics(
                sold.nights(),
                availableNights,
                sold.revenue(),
                ratio(BigDecimal.valueOf(sold.nights()), BigDecimal.valueOf(availableNights)),
                ratio(sold.revenue(), BigDecimal.valueOf(sold.nights())),
                ratio(sold.revenue(), BigDecimal.valueOf(availableNights)),
                statistics.averageLeadTimeDays(propertyIds, from, to)
                        .setScale(1, RoundingMode.HALF_UP),
                ratio(BigDecimal.valueOf(cancels.cancelled()),
                        BigDecimal.valueOf(cancels.total())),
                channelMix(propertyIds, from, to, sold.revenue()));
    }

    /**
     * 판매 가능 객실박. 판매 단위의 수량 합 × 기간 일수다.
     *
     * <p><b>판매중지한 날을 빼지 않는다.</b> 점유율의 분모는 "팔 수 있었던 방"이고,
     * 우리가 팔지 않기로 한 날도 그 방은 있었다. 빼면 판매중지를 늘릴수록 점유율이
     * 올라가는 지표가 된다.
     */
    private long availableNights(List<Long> propertyIds, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        long units = 0;
        for (Long propertyId : propertyIds) {
            for (UnitSummary unit : unitCatalog.summariesOf(propertyId)) {
                units += unit.totalUnits();
            }
        }
        return units * days;
    }

    /** 채널별 비중. 건수와 매출은 booking 이 세고, 나누는 것만 여기서 한다. */
    private List<ChannelShare> channelMix(List<Long> propertyIds, LocalDate from, LocalDate to,
                                          BigDecimal totalRevenue) {
        List<ChannelShare> mix = new ArrayList<>();
        for (BookingStatistics.ChannelVolume volume
                : statistics.channelVolumes(propertyIds, from, to)) {
            mix.add(new ChannelShare(volume.channelCode(), volume.reservations(),
                    volume.revenue(), ratio(volume.revenue(), totalRevenue)));
        }
        return mix;
    }

    // --- 안쪽 -----------------------------------------------------------------

    /**
     * 나눗셈 한 자리.
     *
     * <p><b>분모가 0이면 0이다.</b> 예약이 없는 기간을 보는 것은 정상이고, 그때 화면이
     * 터지면 안 된다. 완료 조건 13 이 이걸 본다.
     */
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0 || numerator == null) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
    }

    private List<Long> targetProperties(Long orgId, Long propertyId) {
        List<Long> ownedIds = owned.propertyIdsOf(orgId);
        if (propertyId == null) {
            return ownedIds;
        }
        // 남의 숙소를 물으면 빈 목록이다. 있는지 없는지 알려 주지 않는다.
        return ownedIds.contains(propertyId) ? List.of(propertyId) : List.of();
    }

    private static ReportMetrics empty() {
        return new ReportMetrics(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }
}
