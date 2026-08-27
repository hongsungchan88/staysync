package com.staysync.property;

import com.staysync.property.domain.Property;
import com.staysync.property.domain.Unit;
import java.time.LocalTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 숙소와 판매 단위의 변경.
 *
 * <p>조회는 {@link OwnedResources} 가 맡는다. 여기서도 소유 확인은 그쪽을 거친다.
 * 변경 경로만 따로 두면 확인을 빠뜨리기 쉽다.
 */
@Service
public class PropertyService {

    private final PropertyRepository propertyRepo;
    private final UnitRepository unitRepo;
    private final UnitRegistrationService unitRegistration;
    private final OwnedResources owned;

    PropertyService(PropertyRepository propertyRepo, UnitRepository unitRepo,
                    UnitRegistrationService unitRegistration, OwnedResources owned) {
        this.propertyRepo = propertyRepo;
        this.unitRepo = unitRepo;
        this.unitRegistration = unitRegistration;
        this.owned = owned;
    }

    /** 숙소를 만든다. 조직은 인증 주체에서 온다. 요청 본문의 조직 식별자는 받지 않는다. */
    @Transactional
    public Property createProperty(Long orgId, String name, String address) {
        Property property = new Property(orgId, name);
        if (address != null) {
            property.changeAddress(address);
        }
        return propertyRepo.save(property);
    }

    @Transactional
    public Property updateProperty(Long propertyId, Long orgId, String name, String address,
                                   LocalTime checkIn, LocalTime checkOut) {
        Property property = owned.property(propertyId, orgId);
        if (name != null) {
            property.rename(name);
        }
        if (address != null) {
            property.changeAddress(address);
        }
        // 두 시각은 함께 바꾼다. 하나만 바꾸면 체크인이 체크아웃보다 늦는 조합이 생긴다.
        if (checkIn != null && checkOut != null) {
            property.changeCheckTimes(checkIn, checkOut);
        }
        return property;
    }

    /**
     * 판매 단위를 등록한다.
     *
     * <p>기본 요금제 자동 생성은 {@link UnitRegistrationService} 가 이미 한다.
     * 여기서는 소유 확인만 더한다.
     */
    @Transactional
    public Unit registerUnit(Long propertyId, Long orgId, String name,
                             com.staysync.property.domain.UnitKind kind,
                             short totalUnits, java.math.BigDecimal basePrice) {
        owned.property(propertyId, orgId);
        Long unitId = unitRegistration.register(propertyId, name, kind, totalUnits, basePrice);
        return unitRepo.findById(unitId).orElseThrow(() -> new UnitNotFoundException(unitId));
    }

    @Transactional
    public Unit updateUnit(Long unitId, Long orgId, String name, Short totalUnits) {
        Unit unit = owned.unit(unitId, orgId);
        if (name != null) {
            unit.rename(name);
        }
        if (totalUnits != null) {
            unit.changeCapacity(totalUnits);
        }
        return unit;
    }
}
