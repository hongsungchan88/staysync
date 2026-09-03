package com.staysync.booking.web;

import com.staysync.booking.calendar.BulkEdit;
import com.staysync.booking.calendar.BulkEditService;
import com.staysync.booking.calendar.CalendarGrid;
import com.staysync.booking.calendar.CalendarService;
import com.staysync.property.OwnedResources;
import com.staysync.property.PropertyNotFoundException;
import com.staysync.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 캘린더 조회 API. 계획서 13.5.
 *
 * <p><b>조직 스코핑이 먼저다.</b> {@link OwnedResources} 로 숙소가 요청자의 조직 것인지
 * 확인하고, 아니면 404 로 답한다. 403 이 아닌 이유는 3주차와 같다 — 남의 숙소가
 * 존재한다는 사실 자체를 알리지 않는다.
 *
 * <p>확인을 통과한 뒤의 그리드 조회는 {@code propertyId} 만으로 돈다. 스코핑과 조회
 * 최적화가 서로 얽히지 않는다.
 */
@RestController
@RequestMapping("/api/properties/{propertyId}/calendar")
class CalendarController {

    private final OwnedResources owned;
    private final CalendarService calendarService;
    private final BulkEditService bulkEditService;

    CalendarController(OwnedResources owned, CalendarService calendarService,
                       BulkEditService bulkEditService) {
        this.owned = owned;
        this.calendarService = calendarService;
        this.bulkEditService = bulkEditService;
    }

    @GetMapping
    CalendarGrid grid(@PathVariable Long propertyId,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        if (!owned.ownsProperty(propertyId, orgId())) {
            throw new PropertyNotFoundException(propertyId);
        }
        return calendarService.assemble(propertyId, from, to);
    }

    /**
     * 요금·제약 일괄 편집. 계획서 8.4.
     *
     * <p><b>미리보기와 적용이 같은 경로다.</b> {@code dryRun} 하나로 갈린다. 별도
     * 엔드포인트로 두면 언젠가 갈라지고, 갈라진 것은 사용자가 적용한 뒤에야 드러난다.
     *
     * <p>조직 스코핑은 조회와 같다. 판매 단위가 이 숙소의 것인지는 그다음에
     * {@code BulkEditWriter} 가 본다.
     */
    @PostMapping("/bulk-edit")
    BulkEdit.Result bulkEdit(@PathVariable Long propertyId,
                             @Valid @RequestBody BulkEditRequest request) {
        if (!owned.ownsProperty(propertyId, orgId())) {
            throw new PropertyNotFoundException(propertyId);
        }
        return bulkEditService.edit(propertyId, request.toDomain());
    }

    /**
     * 요청 본문.
     *
     * <p>도메인 타입을 그대로 받지 않는다. {@code sealed interface} 인
     * {@code PriceChange} 를 JSON 으로 바로 역직렬화하려면 타입 정보를 본문에 넣어야
     * 하는데, 그러면 화면이 자바 타입 이름을 알게 된다.
     *
     * @param priceMode {@code FIXED} 면 {@code price}, {@code PERCENT} 면 {@code priceRate}
     */
    record BulkEditRequest(
            List<Long> unitIds,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Set<DayOfWeek> weekdays,
            String priceMode,
            BigDecimal price,
            BigDecimal priceRate,
            Short minStay,
            Boolean closedToArrival,
            Boolean stopSell,
            boolean dryRun) {

        BulkEdit.Request toDomain() {
            return new BulkEdit.Request(unitIds, from, to, weekdays, priceChange(),
                    minStay, closedToArrival, stopSell, dryRun);
        }

        private BulkEdit.PriceChange priceChange() {
            if (priceMode == null) {
                return null;
            }
            return switch (priceMode) {
                case "FIXED" -> new BulkEdit.PriceChange.Fixed(price);
                case "PERCENT" -> new BulkEdit.PriceChange.Percent(priceRate);
                default -> throw new com.staysync.booking.calendar.InvalidBulkEditException(
                        "요금 변경 방식은 FIXED 또는 PERCENT 여야 합니다. priceMode=" + priceMode);
            };
        }
    }

    private static Long orgId() {
        return AuthenticatedUser.current()
                .orElseThrow(() -> new IllegalStateException(
                        "인증이 필요한 경로인데 주체가 없다. SecurityConfig 설정을 확인할 것."))
                .orgId();
    }
}
