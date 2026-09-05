package com.staysync.booking.web;

import com.staysync.booking.ConflictResolutionService;
import com.staysync.booking.domain.OverbookingConflict;
import com.staysync.booking.domain.Reservation;
import com.staysync.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 중복예약 충돌 관리. 계획서 7.4 이고 방어 4계층의 화면이다.
 *
 * <p><b>{@code /channels/:id/logs} 가 아니다.</b> 충돌은 채널 하나의 문제가 아니라
 * 그 날짜 그 방의 문제다. 채널 로그 아래 두면 어느 채널을 열어야 보이는지 운영자가
 * 알 수 없다.
 *
 * <p>게스트 안내 메시지 초안은 없다. 계획서 7.4 가 9장 AI 기능에 맡겼고 그건 P5 다.
 */
@RestController
@RequestMapping("/api/conflicts")
class ConflictController {

    private final ConflictResolutionService service;

    ConflictController(ConflictResolutionService service) {
        this.service = service;
    }

    @GetMapping
    List<ConflictResponse> list() {
        return service.listOpen(me().orgId()).stream().map(ConflictResponse::from).toList();
    }

    @PostMapping("/{conflictId}/resolve")
    ConflictResponse resolve(@PathVariable Long conflictId,
                             @Valid @RequestBody ResolveRequest request) {
        AuthenticatedUser user = me();
        OverbookingConflict resolved = service.resolve(
                conflictId, user.orgId(), user.userId(), request.resolution(),
                request.reservationId(), request.targetUnitId(), request.memo());
        return ConflictResponse.of(resolved, List.of());
    }

    /**
     * @param reservationId 옮기거나 취소할 예약. 한 자리에 예약이 둘 이상이라
     *                      운영자가 어느 쪽을 움직일지 골라야 한다
     * @param targetUnitId  {@code UPGRADED} 일 때만 쓴다
     */
    record ResolveRequest(@NotBlank String resolution,
                          Long reservationId,
                          Long targetUnitId,
                          @Size(max = 500) String memo) {
    }

    /**
     * 충돌 하나.
     *
     * <p>부딪힌 예약을 함께 담는다. 식별자만 주면 화면이 예약을 하나씩 다시 조회하고,
     * 목록 하나에 요청이 수십 개가 된다.
     *
     * <p><b>게스트 이름과 연락처는 담지 않는다.</b> 운영자가 누구를 옮길지 고르는 데
     * 필요한 것은 예약번호와 채널, 날짜다(ADR 0007 의 선).
     */
    record ConflictResponse(Long id, Long propertyId, Long unitId, LocalDate stayDate,
                            String severity, String status, String resolution,
                            OffsetDateTime detectedAt, OffsetDateTime resolvedAt,
                            List<ConflictReservation> reservations) {

        static ConflictResponse from(ConflictResolutionService.ConflictView view) {
            return of(view.conflict(), view.reservations());
        }

        static ConflictResponse of(OverbookingConflict conflict, List<Reservation> reservations) {
            return new ConflictResponse(
                    conflict.getId(), conflict.getPropertyId(), conflict.getUnitId(),
                    conflict.getStayDate(), conflict.getSeverity(), conflict.getStatus(),
                    conflict.getResolution(), conflict.getDetectedAt(), conflict.getResolvedAt(),
                    reservations.stream().map(ConflictReservation::from).toList());
        }
    }

    record ConflictReservation(Long id, Long unitId, String confirmationCode, String channelCode,
                               String status, LocalDate checkIn, LocalDate checkOut) {

        static ConflictReservation from(Reservation reservation) {
            return new ConflictReservation(
                    reservation.getId(), reservation.getUnitId(),
                    reservation.getConfirmationCode(), reservation.getChannelCode(),
                    reservation.getStatus().name(),
                    reservation.getPeriod().checkIn(), reservation.getPeriod().checkOut());
        }
    }

    private static AuthenticatedUser me() {
        return (AuthenticatedUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
    }
}
