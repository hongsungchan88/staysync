package com.staysync.property.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/** 숙소. 하나의 주소에 해당하며 그 안에 판매 단위를 여러 개 가질 수 있다. */
@Entity
@Table(name = "property")
public class Property {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 50)
    private String timezone = "Asia/Seoul";

    @Column(nullable = false, length = 3)
    private String currency = "KRW";

    @Column(name = "check_in_time", nullable = false)
    private LocalTime checkInTime = LocalTime.of(15, 0);

    @Column(name = "check_out_time", nullable = false)
    private LocalTime checkOutTime = LocalTime.of(11, 0);

    private String address;
    private BigDecimal lat;
    private BigDecimal lng;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Property() {
    }

    public Property(Long orgId, String name) {
        this.orgId = orgId;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Long getOrgId() {
        return orgId;
    }

    public String getName() {
        return name;
    }

    public String getTimezone() {
        return timezone;
    }

    public LocalTime getCheckInTime() {
        return checkInTime;
    }

    public LocalTime getCheckOutTime() {
        return checkOutTime;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void changeCheckTimes(LocalTime in, LocalTime out) {
        this.checkInTime = in;
        this.checkOutTime = out;
    }
}
