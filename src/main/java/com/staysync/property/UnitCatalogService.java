package com.staysync.property;

import com.staysync.property.domain.Unit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class UnitCatalogService implements UnitCatalog {

    private final UnitRepository unitRepo;

    UnitCatalogService(UnitRepository unitRepo) {
        this.unitRepo = unitRepo;
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
}
