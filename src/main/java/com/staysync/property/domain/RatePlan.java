package com.staysync.property.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 요금제.
 *
 * <p>개인 호스트에게는 요금제 개념이 사실상 없지만 테이블을 남긴다. Booking.com 과
 * Channex 가 ARI 전송의 최소 단위로 {@code rate_plan_id} 를 요구하기 때문이다.
 * 없애면 채널 어댑터마다 가짜 요금제 식별자를 만들어 끼우는 우회 코드가 생긴다.
 *
 * <p>대신 판매 단위를 만들 때 기본 요금제를 자동으로 함께 만들어, 호스트가 이
 * 개념을 의식하지 않게 한다.
 */
@Entity
@Table(name = "rate_plan")
public class RatePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private boolean refundable = true;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RatePlan() {
    }

    public RatePlan(Long unitId, String name, boolean isDefault) {
        this.unitId = unitId;
        this.name = name;
        this.isDefault = isDefault;
    }

    /** 판매 단위 생성 시 함께 만들어지는 기본 요금제. */
    public static RatePlan createDefault(Long unitId) {
        return new RatePlan(unitId, "기본", true);
    }

    public Long getId() {
        return id;
    }

    public Long getUnitId() {
        return unitId;
    }

    public String getName() {
        return name;
    }

    public boolean isDefault() {
        return isDefault;
    }
}
