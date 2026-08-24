package com.staysync.property.web;

import com.staysync.property.OwnedResources;
import com.staysync.property.PropertyService;
import com.staysync.property.domain.Property;
import com.staysync.property.web.PropertyDtos.*;
import com.staysync.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 숙소, 판매 단위, 요금제 API.
 *
 * <p>이 컨트롤러는 identity 를 참조하지 않는다. 인증 정보는 {@code SecurityContext} 에서
 * {@link AuthenticatedUser} 로만 읽는다. 참조하면 {@code ModularityTest} 가 깨진다.
 *
 * <p>모든 조회와 변경은 {@link OwnedResources} 를 거쳐 조직으로 좁혀진다. 경로의
 * 식별자는 "무엇을 원하는가"일 뿐 "그것을 볼 수 있는가"의 근거가 아니다.
 */
@RestController
@RequestMapping("/api")
class PropertyController {

    private final OwnedResources owned;
    private final PropertyService propertyService;

    PropertyController(OwnedResources owned, PropertyService propertyService) {
        this.owned = owned;
        this.propertyService = propertyService;
    }

    // --- 숙소 ---------------------------------------------------------------

    @GetMapping("/properties")
    List<PropertySummary> listProperties() {
        return owned.propertiesOf(orgId()).stream()
                .map(PropertySummary::from)
                .toList();
    }

    @PostMapping("/properties")
    ResponseEntity<PropertySummary> createProperty(@Valid @RequestBody CreatePropertyRequest request) {
        Property created = propertyService.createProperty(orgId(), request.name(), request.address());
        return ResponseEntity.status(HttpStatus.CREATED).body(PropertySummary.from(created));
    }

    @GetMapping("/properties/{propertyId}")
    PropertyDetail getProperty(@PathVariable Long propertyId) {
        Long orgId = orgId();
        return PropertyDetail.of(
                owned.property(propertyId, orgId),
                owned.unitsOf(propertyId, orgId));
    }

    @PatchMapping("/properties/{propertyId}")
    PropertySummary updateProperty(@PathVariable Long propertyId,
                                   @Valid @RequestBody UpdatePropertyRequest request) {
        return PropertySummary.from(propertyService.updateProperty(
                propertyId, orgId(), request.name(), request.address(),
                request.checkInTime(), request.checkOutTime()));
    }

    // --- 판매 단위 -----------------------------------------------------------

    @GetMapping("/properties/{propertyId}/units")
    List<UnitResponse> listUnits(@PathVariable Long propertyId) {
        return owned.unitsOf(propertyId, orgId()).stream()
                .map(UnitResponse::from)
                .toList();
    }

    @PostMapping("/properties/{propertyId}/units")
    ResponseEntity<UnitResponse> createUnit(@PathVariable Long propertyId,
                                            @Valid @RequestBody CreateUnitRequest request) {
        UnitResponse created = UnitResponse.from(propertyService.registerUnit(
                propertyId, orgId(), request.name(), request.unitKind(),
                request.totalUnits(), request.basePrice()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/units/{unitId}")
    UnitResponse updateUnit(@PathVariable Long unitId,
                            @Valid @RequestBody UpdateUnitRequest request) {
        return UnitResponse.from(propertyService.updateUnit(
                unitId, orgId(), request.name(), request.totalUnits()));
    }

    // --- 요금제 -------------------------------------------------------------

    @GetMapping("/units/{unitId}/rate-plans")
    List<RatePlanResponse> listRatePlans(@PathVariable Long unitId) {
        return owned.ratePlansOf(unitId, orgId()).stream()
                .map(RatePlanResponse::from)
                .toList();
    }

    /**
     * 현재 요청의 조직.
     *
     * <p>보호된 경로라 주체가 없을 수 없다. 없다면 {@code SecurityConfig} 가 이 경로를
     * 열어 둔 것이므로 설정 실수다.
     */
    private static Long orgId() {
        return AuthenticatedUser.current()
                .orElseThrow(() -> new IllegalStateException(
                        "인증이 필요한 경로인데 주체가 없다. SecurityConfig 설정을 확인할 것."))
                .orgId();
    }
}
