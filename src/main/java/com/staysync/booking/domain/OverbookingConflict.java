package com.staysync.booking.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 재고를 넘겨 받아들인 예약의 기록. 날짜 하나가 한 행이다.
 *
 * <p><b>이 행이 생긴다는 것은 거절하지 않았다는 뜻이다.</b> OTA 에서 이미 성사된 예약을
 * 재고 없음으로 거절하면 게스트와 플랫폼 양쪽에서 문제가 된다. 받아들이고 여기 남겨
 * 운영자가 업그레이드·대체 숙소·취소 중에서 고르게 한다.
 *
 * <p>해소 절차 넷과 관리 화면은 13주차다(계획서 7.4). 12주차는 <b>기록까지</b>이고,
 * 캘린더의 {@code conflict} 표시가 이 행을 읽는다.
 *
 * <p>{@code reservationIds} 에 <b>그날 겹치는 예약을 전부</b> 담는다. 새 예약 하나만
 * 담으면 "충돌"인데 상대가 없는 행이 되어, 13주차에 무엇과 무엇이 부딪혔는지 다시
 * 찾아야 한다.
 */
@Entity
@Table(name = "overbooking_conflict")
public class OverbookingConflict {

    /** 재고를 실제로 넘긴 경우. 계획서 13.2 가 이 값을 쓴다. */
    public static final String CRITICAL = "CRITICAL";

    public static final String OPEN = "OPEN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(name = "stay_date", nullable = false)
    private LocalDate stayDate;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "reservation_ids", nullable = false)
    private Long[] reservationIds;

    @Column(nullable = false, length = 10)
    private String severity = CRITICAL;

    @Column(nullable = false, length = 20)
    private String status = OPEN;

    @Column(name = "detected_at", insertable = false, updatable = false)
    private OffsetDateTime detectedAt;

    protected OverbookingConflict() {
    }

    public OverbookingConflict(Long propertyId, Long unitId, LocalDate stayDate,
                               List<Long> reservationIds) {
        this.propertyId = propertyId;
        this.unitId = unitId;
        this.stayDate = stayDate;
        this.reservationIds = reservationIds.toArray(Long[]::new);
    }

    public Long getId() {
        return id;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getUnitId() {
        return unitId;
    }

    public LocalDate getStayDate() {
        return stayDate;
    }

    public List<Long> getReservationIds() {
        return List.of(reservationIds);
    }

    public String getSeverity() {
        return severity;
    }

    public String getStatus() {
        return status;
    }
}
