package com.staysync.pricing;

import com.staysync.pricing.domain.RateCalendar;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class RateCalendarViewService implements RateCalendarView {

    private final RateCalendarRepository rateRepo;

    RateCalendarViewService(RateCalendarRepository rateRepo) {
        this.rateRepo = rateRepo;
    }

    @Override
    public List<DayRate> ratesOf(List<Long> ratePlanIds, LocalDate from, LocalDate to) {
        if (ratePlanIds.isEmpty()) {
            // 판매 단위가 없거나 기본 요금제가 붙지 않은 숙소다. 빈 IN 절로 질의하면
            // 데이터베이스마다 동작이 다르므로 아예 나가지 않는다.
            return List.of();
        }
        return rateRepo.findGrid(ratePlanIds, from, to).stream()
                .map(RateCalendarViewService::toDayRate)
                .toList();
    }

    private static DayRate toDayRate(RateCalendar row) {
        return new DayRate(row.getRatePlanId(), row.getStayDate(),
                row.getPrice(), row.getMinStay());
    }
}
