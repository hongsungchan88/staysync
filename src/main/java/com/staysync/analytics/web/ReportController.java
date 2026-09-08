package com.staysync.analytics.web;

import com.staysync.analytics.ReportMetrics;
import com.staysync.analytics.ReportService;
import com.staysync.shared.security.AuthenticatedUser;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 리포트 API. 계획서 8.8 의 화면이 쓴다.
 *
 * <p>화면 하나이고 응답도 하나다. 지표마다 엔드포인트를 두면 한 화면이 여섯 번
 * 왕복하고, 그 여섯이 서로 다른 순간의 값을 보여 줄 수 있다.
 */
@RestController
@RequestMapping("/api/reports")
class ReportController {

    private final ReportService service;

    ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping
    ReportMetrics report(
            @RequestParam(required = false) Long propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.of(orgId(), propertyId, from, to);
    }

    private static Long orgId() {
        return AuthenticatedUser.current()
                .orElseThrow(() -> new IllegalStateException(
                        "인증이 필요한 경로인데 주체가 없다. SecurityConfig 설정을 확인할 것."))
                .orgId();
    }
}
