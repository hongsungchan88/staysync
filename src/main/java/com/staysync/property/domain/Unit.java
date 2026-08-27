package com.staysync.property.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 판매 단위. 이 프로젝트에서 재고와 요금이 붙는 유일한 축이다.
 *
 * <p>객실타입과 개별 객실을 나누지 않고 하나로 합쳤다. 대상 사용자인 개인 호스트에게는
 * 판매 상품이 곧 물리 공간이라 두 계층이 항상 1:1 이 되기 때문이다. 도미토리처럼 같은
 * 조건의 자리를 여러 개 파는 경우는 {@code totalUnits} 로 표현한다.
 * (자세한 근거는 docs/결정문서-01-데이터모델.md 참고)
 */
@Entity
@Table(name = "unit")
public class Unit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_kind", nullable = false, length = 20)
    private UnitKind unitKind;

    @Column(name = "occupancy_std", nullable = false)
    private short occupancyStd = 2;

    @Column(name = "occupancy_max", nullable = false)
    private short occupancyMax = 4;

    /** 동시에 판매 가능한 수량. 독채는 1, 4인 도미토리는 4. */
    @Column(name = "total_units", nullable = false)
    private short totalUnits = 1;

    @Column(name = "base_price", nullable = false)
    private BigDecimal basePrice = BigDecimal.ZERO;

    /** 요금 추천의 하한. 설정하지 않으면 자동 적용 모드를 켤 수 없다. */
    @Column(name = "floor_price")
    private BigDecimal floorPrice;

    /** 요금 추천의 상한. */
    @Column(name = "ceiling_price")
    private BigDecimal ceilingPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Housekeeping housekeeping = Housekeeping.CLEAN;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Unit() {
    }

    public Unit(Long propertyId, String name, UnitKind unitKind, short totalUnits, BigDecimal basePrice) {
        if (totalUnits < 1) {
            throw new IllegalArgumentException("판매 수량은 1 이상이어야 합니다.");
        }
        this.propertyId = propertyId;
        this.name = name;
        this.unitKind = unitKind;
        this.totalUnits = totalUnits;
        this.basePrice = basePrice;
    }

    /** 독채 한 채를 통째로 파는 가장 흔한 경우. */
    public static Unit entirePlace(Long propertyId, String name, BigDecimal basePrice) {
        return new Unit(propertyId, name, UnitKind.ENTIRE_PLACE, (short) 1, basePrice);
    }

    /** 도미토리처럼 같은 조건의 자리를 여러 개 파는 경우. */
    public static Unit dormitory(Long propertyId, String name, short beds, BigDecimal basePrice) {
        return new Unit(propertyId, name, UnitKind.SHARED_ROOM, beds, basePrice);
    }

    public Long getId() {
        return id;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public String getName() {
        return name;
    }

    public UnitKind getUnitKind() {
        return unitKind;
    }

    public short getTotalUnits() {
        return totalUnits;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public Housekeeping getHousekeeping() {
        return housekeeping;
    }

    /** 요금 추천 자동 적용이 가능한지. 하한과 상한이 모두 설정되어야 한다. */
    public boolean canAutoApplyRates() {
        return floorPrice != null && ceilingPrice != null;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void markDirty() {
        this.housekeeping = Housekeeping.DIRTY;
    }

    public void markClean() {
        this.housekeeping = Housekeeping.CLEAN;
    }

    public void changeCapacity(short totalUnits) {
        if (totalUnits < 1) {
            throw new IllegalArgumentException("판매 수량은 1 이상이어야 합니다.");
        }
        this.totalUnits = totalUnits;
    }
}
