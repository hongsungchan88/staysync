package com.staysync.property;

import com.staysync.property.domain.RatePlan;
import com.staysync.property.domain.Unit;
import com.staysync.property.domain.UnitKind;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매 단위를 등록한다.
 *
 * <p>등록과 동시에 기본 요금제를 함께 만든다. 개인 호스트는 요금제라는 개념을
 * 쓰지 않지만 Booking.com 과 Channex 가 ARI 전송에 요금제 식별자를 요구하므로,
 * 화면에서는 숨기고 데이터로만 유지한다.
 */
@Service
public class UnitRegistrationService {

    private final UnitRepository unitRepo;
    private final RatePlanRepository ratePlanRepo;

    public UnitRegistrationService(UnitRepository unitRepo, RatePlanRepository ratePlanRepo) {
        this.unitRepo = unitRepo;
        this.ratePlanRepo = ratePlanRepo;
    }

    @Transactional
    public Long register(Long propertyId, String name, UnitKind kind,
                         short totalUnits, BigDecimal basePrice) {
        Unit unit = unitRepo.save(new Unit(propertyId, name, kind, totalUnits, basePrice));
        ratePlanRepo.save(RatePlan.createDefault(unit.getId()));
        return unit.getId();
    }
}
