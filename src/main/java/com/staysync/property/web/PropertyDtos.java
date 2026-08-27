package com.staysync.property.web;

import com.staysync.property.domain.Property;
import com.staysync.property.domain.RatePlan;
import com.staysync.property.domain.Unit;
import com.staysync.property.domain.UnitKind;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * 숙소 API 의 요청·응답 본문.
 *
 * <p>엔티티를 그대로 내보내지 않는다. 내보내면 모듈 내부 구현이 HTTP 계약이 되어,
 * 컬럼 하나를 바꾸는 일이 API 변경이 된다.
 *
 * <p>목록은 평면이고 상세에만 {@code units} 를 싣는다. 페이지네이션은 두지 않는다.
 * 개인 호스트는 숙소가 한두 개다.
 */
final class PropertyDtos {

    private PropertyDtos() {
    }

    // --- 요청 ----------------------------------------------------------------

    /** 조직 식별자를 받지 않는다. 인증 주체에서 가져온다. */
    record CreatePropertyRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 500) String address) {
    }

    record UpdatePropertyRequest(
            @Size(max = 200) String name,
            @Size(max = 500) String address,
            LocalTime checkInTime,
            LocalTime checkOutTime) {
    }

    record CreateUnitRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull UnitKind unitKind,
            @NotNull @Min(1) @Max(999) Short totalUnits,
            @NotNull @DecimalMin("0") BigDecimal basePrice) {
    }

    record UpdateUnitRequest(
            @Size(max = 200) String name,
            @Min(1) @Max(999) Short totalUnits) {
    }

    // --- 응답 ----------------------------------------------------------------

    /** 목록용. 판매 단위를 싣지 않는다. */
    record PropertySummary(
            Long id, String name, String address, String timezone,
            String currency, LocalTime checkInTime, LocalTime checkOutTime, String status) {

        static PropertySummary from(Property property) {
            return new PropertySummary(
                    property.getId(), property.getName(), property.getAddress(),
                    property.getTimezone(), property.getCurrency(),
                    property.getCheckInTime(), property.getCheckOutTime(), property.getStatus());
        }
    }

    /** 상세용. 판매 단위를 함께 싣는다. */
    record PropertyDetail(PropertySummary property, List<UnitResponse> units) {

        static PropertyDetail of(Property property, List<Unit> units) {
            return new PropertyDetail(
                    PropertySummary.from(property),
                    units.stream().map(UnitResponse::from).toList());
        }
    }

    record UnitResponse(
            Long id, Long propertyId, String name, UnitKind unitKind,
            short totalUnits, BigDecimal basePrice, String housekeeping) {

        static UnitResponse from(Unit unit) {
            return new UnitResponse(
                    unit.getId(), unit.getPropertyId(), unit.getName(), unit.getUnitKind(),
                    unit.getTotalUnits(), unit.getBasePrice(), unit.getHousekeeping().name());
        }
    }

    /**
     * 요금제는 조회만 연다.
     *
     * <p>판매 단위를 만들 때 기본 요금제가 자동으로 함께 생기므로 생성 엔드포인트를
     * 두지 않는다. 개인 호스트가 이 개념을 의식할 일이 없어야 한다.
     */
    record RatePlanResponse(Long id, Long unitId, String name, boolean isDefault) {

        static RatePlanResponse from(RatePlan plan) {
            return new RatePlanResponse(
                    plan.getId(), plan.getUnitId(), plan.getName(), plan.isDefault());
        }
    }
}
