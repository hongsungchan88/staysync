package com.staysync.property;

import com.staysync.property.domain.RatePlan;
import com.staysync.property.domain.Unit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class UnitCatalogService implements UnitCatalog {

    private final UnitRepository unitRepo;
    private final RatePlanRepository ratePlanRepo;
    private final PropertyRepository propertyRepo;

    UnitCatalogService(UnitRepository unitRepo, RatePlanRepository ratePlanRepo,
                       PropertyRepository propertyRepo) {
        this.unitRepo = unitRepo;
        this.ratePlanRepo = ratePlanRepo;
        this.propertyRepo = propertyRepo;
    }

    @Override
    public short totalUnitsOf(Long unitId) {
        return unitRepo.findById(unitId)
                .map(Unit::getTotalUnits)
                .orElseThrow(() -> new UnitNotFoundException(unitId));
    }

    @Override
    public List<Long> unitIdsOf(Long propertyId) {
        return unitRepo.findByPropertyIdOrderBySortOrderAscIdAsc(propertyId).stream()
                .map(Unit::getId)
                .toList();
    }

    @Override
    public String nameOf(Long unitId) {
        return unitRepo.findById(unitId)
                .map(Unit::getName)
                .orElseThrow(() -> new UnitNotFoundException(unitId));
    }

    @Override
    public String propertyNameOf(Long propertyId) {
        return propertyRepo.findById(propertyId)
                .map(com.staysync.property.domain.Property::getName)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));
    }

    @Override
    public UnitSummary summaryOf(Long unitId) {
        return unitRepo.findById(unitId)
                .map(this::toSummary)
                .orElseThrow(() -> new UnitNotFoundException(unitId));
    }

    @Override
    public List<UnitSummary> summariesOf(Long propertyId) {
        return unitRepo.findByPropertyIdOrderBySortOrderAscIdAsc(propertyId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public StayTimes stayTimesOf(Long propertyId) {
        return propertyRepo.findById(propertyId)
                .map(property -> new StayTimes(
                        property.getCheckInTime(), property.getCheckOutTime()))
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));
    }

    private UnitSummary toSummary(Unit unit) {
        // 기본 요금제는 판매 단위 등록 시 자동 생성되지만(UnitRegistrationService),
        // 없는 경우를 예외로 만들지는 않는다. 캘린더는 요금제가 없으면 base_price 로
        // 떨어지면 되고, 그 화면이 요금제 누락 때문에 통째로 막힐 이유가 없다.
        Long ratePlanId = ratePlanRepo.findByUnitIdAndIsDefaultTrue(unit.getId())
                .map(RatePlan::getId)
                .orElse(null);
        return new UnitSummary(unit.getId(), unit.getPropertyId(), unit.getName(),
                unit.getTotalUnits(), unit.getBasePrice(), ratePlanId);
    }
}
