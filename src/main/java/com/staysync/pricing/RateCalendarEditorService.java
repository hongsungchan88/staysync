package com.staysync.pricing;

import com.staysync.pricing.domain.RateCalendar;
import com.staysync.pricing.domain.RateCalendarId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RateCalendarEditorService implements RateCalendarEditor {

    private final RateCalendarRepository rateRepo;

    RateCalendarEditorService(RateCalendarRepository rateRepo) {
        this.rateRepo = rateRepo;
    }

    /**
     * 부르는 쪽의 트랜잭션에 합류한다({@code REQUIRED} 가 기본값이다).
     *
     * <p>{@code REQUIRES_NEW} 를 쓰면 판매중지 갱신이 실패해도 요금은 남는다. 판별 기준은
     * 5~6주차와 같다 — 바깥이 롤백될 때 이 쓰기가 남아야 하는가, 사라져야 하는가.
     * 여기는 사라져야 한다.
     */
    @Override
    @Transactional
    public int applyAll(List<RateChange> changes) {
        if (changes.isEmpty()) {
            return 0;
        }

        List<RateCalendar> rows = new ArrayList<>(changes.size());
        for (RateChange change : changes) {
            RateCalendar row = rateRepo
                    .findById(new RateCalendarId(change.ratePlanId(), change.date()))
                    .orElseGet(() -> new RateCalendar(
                            change.ratePlanId(), change.date(), change.price(), change.minStay()));
            row.edit(change.price(), change.minStay(), change.closedToArrival());
            rows.add(row);
        }
        rateRepo.saveAll(rows);
        return rows.size();
    }
}
