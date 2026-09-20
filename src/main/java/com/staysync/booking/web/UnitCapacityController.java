package com.staysync.booking.web;

import com.staysync.booking.UnitCapacityService;
import com.staysync.property.UnitSummary;
import com.staysync.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매 단위 수량 변경. 작업지시-19 C.
 *
 * <p>{@code PATCH /api/units/{id}} 는 property 의 것이고 이름만 바꾼다. 수량은 여기다 —
 * 원장이 따라가야 하는 쓰기라 booking 이 든다. 응답은 property 가 공개하는
 * {@code UnitSummary} 의 필드다(종류·청소 상태는 없다 — 화면은 저장 뒤 목록을 다시 읽는다).
 *
 * <p>줄이기가 막히면 409 {@code CAPACITY_BELOW_BOOKINGS} 이고 {@code details} 에 막는 날짜들이
 * 실린다.
 */
@RestController
@RequestMapping("/api/units")
class UnitCapacityController {

    private final UnitCapacityService capacity;

    UnitCapacityController(UnitCapacityService capacity) {
        this.capacity = capacity;
    }

    @PatchMapping("/{unitId}/capacity")
    UnitCapacityResponse change(@PathVariable Long unitId,
                                @Valid @RequestBody CapacityRequest request) {
        return UnitCapacityResponse.from(capacity.change(unitId, orgId(), request.totalUnits()));
    }

    record CapacityRequest(@NotNull @Min(1) @Max(999) Short totalUnits) {
    }

    record UnitCapacityResponse(Long id, Long propertyId, String name,
                                short totalUnits, BigDecimal basePrice) {

        static UnitCapacityResponse from(UnitSummary unit) {
            return new UnitCapacityResponse(
                    unit.id(), unit.propertyId(), unit.name(), unit.totalUnits(), unit.basePrice());
        }
    }

    private static Long orgId() {
        return AuthenticatedUser.current()
                .orElseThrow(() -> new IllegalStateException(
                        "인증이 필요한 경로인데 주체가 없다. SecurityConfig 설정을 확인할 것."))
                .orgId();
    }
}
