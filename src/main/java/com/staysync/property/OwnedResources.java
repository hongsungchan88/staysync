package com.staysync.property;

import com.staysync.property.domain.Property;
import com.staysync.property.domain.RatePlan;
import com.staysync.property.domain.Unit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조직 스코핑을 한곳에 모은다.
 *
 * <p>이 작업의 핵심이다. 모든 조회와 변경은 인증 주체의 {@code orgId} 로 좁혀야 하고,
 * 요청 경로나 본문에 담긴 조직 식별자를 믿어서는 안 된다.
 *
 * <p>문제는 {@code unit} 과 {@code rate_plan} 에 {@code org_id} 가 없다는 것이다.
 * 각각 {@code property_id} 와 {@code unit_id} 를 거슬러 올라가야 소유를 확인할 수 있다.
 * 그 걸음을 컨트롤러마다 손으로 반복하면 한 곳만 빠뜨려도 다른 조직의 데이터가 샌다.
 * 그래서 세 계층의 진입점을 여기로 모으고, 컨트롤러는 반드시 이 클래스를 거친다.
 *
 * <p>소유가 아닌 자원은 "없음"으로 답한다. 403 으로 존재를 알려 주면 식별자를 훑어
 * 다른 조직의 자원 존재 여부를 알아낼 수 있다.
 */
@Service
@Transactional(readOnly = true)
public class OwnedResources {

    private final PropertyRepository propertyRepo;
    private final UnitRepository unitRepo;
    private final RatePlanRepository ratePlanRepo;

    OwnedResources(PropertyRepository propertyRepo, UnitRepository unitRepo,
                   RatePlanRepository ratePlanRepo) {
        this.propertyRepo = propertyRepo;
        this.unitRepo = unitRepo;
        this.ratePlanRepo = ratePlanRepo;
    }

    /** 조직의 숙소 전부. */
    public List<Property> propertiesOf(Long orgId) {
        return propertyRepo.findByOrgId(orgId);
    }

    /** 1계층 — 숙소는 {@code org_id} 를 직접 들고 있다. */
    public Property property(Long propertyId, Long orgId) {
        return propertyRepo.findById(propertyId)
                .filter(property -> property.getOrgId().equals(orgId))
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));
    }

    /** 2계층 — 판매 단위는 숙소를 거쳐야 조직을 알 수 있다. */
    public Unit unit(Long unitId, Long orgId) {
        Unit unit = unitRepo.findById(unitId)
                .orElseThrow(() -> new UnitNotFoundException(unitId));
        // 숙소 조회가 소유를 확인한다. 남의 것이면 여기서 걸린다.
        // 다만 바깥에는 숙소가 아니라 판매 단위가 없다고 답해야 한다.
        if (!ownsProperty(unit.getPropertyId(), orgId)) {
            throw new UnitNotFoundException(unitId);
        }
        return unit;
    }

    public List<Unit> unitsOf(Long propertyId, Long orgId) {
        property(propertyId, orgId);   // 소유 확인이 목적이다
        return unitRepo.findByPropertyIdOrderBySortOrderAscIdAsc(propertyId);
    }

    /** 3계층 — 요금제는 판매 단위와 숙소를 차례로 거친다. */
    public List<RatePlan> ratePlansOf(Long unitId, Long orgId) {
        unit(unitId, orgId);           // 소유 확인이 목적이다
        return ratePlanRepo.findByUnitId(unitId);
    }

    private boolean ownsProperty(Long propertyId, Long orgId) {
        return propertyRepo.findById(propertyId)
                .map(property -> property.getOrgId().equals(orgId))
                .orElse(false);
    }
}
