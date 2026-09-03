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

    /** 조직의 숙소 전부. property 모듈 안에서만 쓴다. {@link Property} 가 내부 타입이다. */
    public List<Property> propertiesOf(Long orgId) {
        return propertyRepo.findByOrgId(orgId);
    }

    /**
     * 조직의 숙소 식별자만.
     *
     * <p>{@link #propertiesOf} 는 {@code property.domain.Property} 를 돌려주므로 다른
     * 모듈이 쓸 수 없다. booking 처럼 "내 조직의 숙소가 어떤 것들인가"로 목록을 좁히기만
     * 하면 되는 쪽을 위해 식별자만 돌려준다.
     */
    public List<Long> propertyIdsOf(Long orgId) {
        return propertyRepo.findByOrgId(orgId).stream()
                .map(Property::getId)
                .toList();
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

    /**
     * 숙소가 이 조직의 것인지.
     *
     * <p>다른 모듈이 쓸 수 있는 유일한 소유 확인 통로다. {@link #property} 는
     * {@code property.domain.Property} 를 돌려주는데 그건 모듈 내부 타입이라 바깥에서
     * 참조하면 {@code ModularityTest} 가 깨진다. 그래서 booking 처럼 자기 자원이 어느
     * 조직에 속하는지만 알면 되는 쪽을 위해 boolean 만 돌려주는 메서드를 열어 둔다.
     */
    public boolean ownsProperty(Long propertyId, Long orgId) {
        return propertyRepo.findById(propertyId)
                .map(property -> property.getOrgId().equals(orgId))
                .orElse(false);
    }

    /**
     * 숙소를 가진 조직. 실시간 갱신이 "이 사건을 누구에게 보낼지" 고를 때 쓴다.
     *
     * <p>{@link #ownsProperty} 로 구독자마다 물어도 답은 같지만, 구독자 수만큼 질의가
     * 늘고 무엇보다 <b>기본값이 반대</b>다. 저쪽은 모르면 false 라 안전하고, 여기는
     * 모르면 빈 값이라 아무에게도 보내지 않는다. 둘 다 "모르면 보내지 않는다"로
     * 떨어져야 한다.
     */
    public java.util.Optional<Long> orgIdOfProperty(Long propertyId) {
        return propertyRepo.findById(propertyId).map(Property::getOrgId);
    }
}
