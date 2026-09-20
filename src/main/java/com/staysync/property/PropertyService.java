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

    /** 소유 확인만. 없거나 남의 것이면 {@code UnitNotFoundException}. booking 이 락을 잡기 전에 부른다. */
    @Transactional(readOnly = true)
    public void requireOwnedUnit(Long unitId, Long orgId) {
        owned.unit(unitId, orgId);
    }

    /** 이름만 바꾼다. 수량은 {@link #changeUnitCapacity} — 원장이 따라가야 해서 booking 이 든다. */
    @Transactional
    public Unit updateUnit(Long unitId, Long orgId, String name) {
        Unit unit = owned.unit(unitId, orgId);
        if (name != null) {
            unit.rename(name);
        }
        return unit;
    }

    /**
     * 판매 수량을 바꾼다. <b>직접 부르지 말 것</b> — 원장 행이 함께 바뀌어야 하므로
     * booking 의 {@code UnitCapacityService} 가 판매 단위 락과 트랜잭션 안에서 부른다
     * (작업지시-19 C). 여기서 따로 부르면 원장이 옛 수량으로 남는다(작업지시-18 9.4.1 의
     * 결함이 그 모양이었다).
     */
    @Transactional
    public UnitSummary changeUnitCapacity(Long unitId, Long orgId, short totalUnits) {
        Unit unit = owned.unit(unitId, orgId);
        unit.changeCapacity(totalUnits);
        // booking 에 돌려주는 값이라 도메인 엔티티가 아니라 공개 타입이다(모듈 경계).
        return new UnitSummary(unit.getId(), unit.getPropertyId(), unit.getName(),
                unit.getTotalUnits(), unit.getBasePrice(), null);
    }
}
