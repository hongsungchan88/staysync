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
 * <p>해소 절차 넷과 관리 화면이 13주차에 붙었다(계획서 7.4). 캘린더의
 * {@code conflict} 표시는 {@code OPEN} 인 행만 읽는다 — 운영자가 이미 처리한 것을
 * 계속 붉게 두면 표시가 의미를 잃는다.
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

    public static final String RESOLVED = "RESOLVED";

    /**
     * 해소 방법 넷. 계획서 7.4 그대로다. {@code resolution} 컬럼의 주석과 짝이다.
     *
     * <p><b>업그레이드만 재고를 움직인다.</b> 나머지 셋은 기록이고, 취소는 예약
     * 상태를 바꾼다. 그 차이가 락을 몇 개 쥐느냐를 가른다.
     */
    public static final String UPGRADED = "UPGRADED";

    /** 인근 제휴 숙소 안내. 우리 재고 밖의 일이라 기록만 남는다. */
    public static final String RELOCATED = "RELOCATED";

    /** 취소와 보상. 예약을 취소하므로 재고가 돌아온다. */
    public static final String CANCELLED = "CANCELLED";

    /** 실제 추가 객실이 있어 관리자가 허용한다. 재고를 그대로 둔다. */
    public static final String ABSORBED = "ABSORBED";

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

    @Column(length = 40)
    private String resolution;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column
    private String memo;

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

    public String getResolution() {
        return resolution;
    }

    public Long getResolvedBy() {
        return resolvedBy;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public String getMemo() {
        return memo;
    }

    public OffsetDateTime getDetectedAt() {
        return detectedAt;
    }

    /**
     * 해소했다고 기록한다. 계획서 7.4 의 넷 가운데 하나를 고른 결과다.
     *
     * <p><b>멱등하지 않다.</b> 이미 해소된 행을 다시 해소하면 예외다 — 업그레이드
     * 배정은 재고를 옮기는 일이라, 두 번 부르면 방이 두 번 옮겨진다.
     *
     * <p>{@code resolvedBy} 는 사람이다. 배치가 부르는 경로가 아니라 관리 화면에서
     * 운영자가 고른 것이라 {@code null} 일 수 없다.
     */
    public void resolve(String resolution, Long resolvedBy, String memo) {
        if (!OPEN.equals(status)) {
            throw new ConflictAlreadyResolvedException(id, status);
        }
        this.status = RESOLVED;
        this.resolution = resolution;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = OffsetDateTime.now();
        this.memo = memo;
    }
}
