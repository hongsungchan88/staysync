package com.staysync.booking.web;

import com.staysync.booking.calendar.CalendarGrid;
import com.staysync.booking.calendar.CalendarService;
import com.staysync.property.OwnedResources;
import com.staysync.property.PropertyNotFoundException;
import com.staysync.shared.security.AuthenticatedUser;
import java.time.LocalDate;
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

    CalendarController(OwnedResources owned, CalendarService calendarService) {
        this.owned = owned;
        this.calendarService = calendarService;
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

    private static Long orgId() {
        return AuthenticatedUser.current()
                .orElseThrow(() -> new IllegalStateException(
                        "인증이 필요한 경로인데 주체가 없다. SecurityConfig 설정을 확인할 것."))
                .orgId();
    }
}
