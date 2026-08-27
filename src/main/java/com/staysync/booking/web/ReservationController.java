package com.staysync.booking.web;

import com.staysync.booking.*;
import com.staysync.booking.domain.Guest;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.ReservationStatus;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.booking.web.ReservationDtos.*;
import com.staysync.property.OwnedResources;
import com.staysync.property.UnitCatalog;
import com.staysync.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 예약 API.
 *
 * <p>조직 스코핑은 3주차에 만든 {@link OwnedResources} 를 쓴다. {@code reservation} 에는
 * {@code org_id} 가 없어 {@code property_id} 로 거슬러 올라가야 한다. 소유가 아닌 자원은
 * 3주차와 같게 404 로 답한다. 403 이면 식별자를 훑어 존재 여부를 알아낼 수 있다.
 *
 * <p>property 모듈에서 쓰는 것은 최상위 패키지에 공개된 {@link OwnedResources} 와
 * {@link UnitCatalog} 뿐이다. {@code property.domain} 을 참조하면
 * {@code ModularityTest} 가 깨진다.
 */
@RestController
@RequestMapping("/api/reservations")
class ReservationController {

    private final BookingService bookingService;
    private final GuestRegistrar guestRegistrar;
    private final ReservationRepository reservationRepo;
    private final ReservationNightRepository nightRepo;
    private final GuestRepository guestRepo;
    private final OwnedResources owned;
    private final UnitCatalog unitCatalog;

    ReservationController(BookingService bookingService,
                          GuestRegistrar guestRegistrar,
                          ReservationRepository reservationRepo,
                          ReservationNightRepository nightRepo,
                          GuestRepository guestRepo,
                          OwnedResources owned,
                          UnitCatalog unitCatalog) {
        this.bookingService = bookingService;
        this.guestRegistrar = guestRegistrar;
        this.reservationRepo = reservationRepo;
        this.nightRepo = nightRepo;
        this.guestRepo = guestRepo;
        this.owned = owned;
        this.unitCatalog = unitCatalog;
    }

    @GetMapping
    List<ReservationSummary> list(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) String channelCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        // 조직의 숙소 식별자로 먼저 좁힌다. 목록 조회에서 다른 조직의 예약이 섞이지 않게
        // 하는 유일한 방어선이라, 빈 목록이어도 이 경로를 그대로 탄다.
        List<Long> propertyIds = ownedPropertyIds();
        if (propertyIds.isEmpty()) {
            return List.of();
        }
        return reservationRepo.search(propertyIds, status, channelCode, from, to).stream()
                .map(ReservationSummary::from)
                .toList();
    }

    @GetMapping("/{reservationId}")
    ReservationDetail get(@PathVariable Long reservationId) {
        Reservation reservation = requireOwned(reservationId);
        String guestName = guestNameOf(reservation);
        return ReservationDetail.of(
                reservation, guestName,
                nightRepo.findByReservationIdOrderByStayDateAsc(reservationId));
    }

    @PostMapping
    ResponseEntity<ReservationSummary> create(@Valid @RequestBody CreateReservationRequest request) {
        Long orgId = orgId();
        requireOwnedUnit(request.propertyId(), request.unitId(), orgId);

        Long guestId = null;
        if (request.guestName() != null && !request.guestName().isBlank()) {
            guestId = guestRegistrar.register(
                    orgId, request.guestName(), request.guestPhone(), request.guestEmail()).getId();
        }

        Reservation created = bookingService.registerManual(
                request.propertyId(), request.unitId(),
                new StayPeriod(request.checkIn(), request.checkOut()),
                request.totalAmount(),
                request.adults(),
                request.children() == null ? 0 : request.children(),
                guestId);

        return ResponseEntity.status(HttpStatus.CREATED).body(ReservationSummary.from(created));
    }

    @PatchMapping("/{reservationId}")
    ReservationSummary update(@PathVariable Long reservationId,
                              @Valid @RequestBody UpdateReservationRequest request) {
        Reservation existing = requireOwned(reservationId);

        StayPeriod newPeriod = new StayPeriod(
                request.checkIn() == null ? existing.getPeriod().checkIn() : request.checkIn(),
                request.checkOut() == null ? existing.getPeriod().checkOut() : request.checkOut());
        short adults = request.adults() == null ? existing.getAdults() : request.adults();
        short children = request.children() == null ? existing.getChildren() : request.children();

        return ReservationSummary.from(
                bookingService.changeStay(reservationId, newPeriod, adults, children));
    }

    @PostMapping("/{reservationId}/cancel")
    ReservationSummary cancel(@PathVariable Long reservationId) {
        requireOwned(reservationId);
        return ReservationSummary.from(bookingService.cancel(reservationId));
    }

    @PostMapping("/{reservationId}/check-in")
    ReservationSummary checkIn(@PathVariable Long reservationId) {
        requireOwned(reservationId);
        return ReservationSummary.from(bookingService.checkIn(reservationId));
    }

    @PostMapping("/{reservationId}/check-out")
    ReservationSummary checkOut(@PathVariable Long reservationId) {
        requireOwned(reservationId);
        return ReservationSummary.from(bookingService.checkOut(reservationId));
    }

    // --- 조직 스코핑 ---------------------------------------------------------

    /**
     * 예약을 읽고 소유를 확인한다.
     *
     * <p>모든 단건 경로가 이걸 먼저 거친다. 확인을 컨트롤러마다 손으로 반복하면 한 곳만
     * 빠뜨려도 다른 조직의 예약이 새어 나간다.
     */
    private Reservation requireOwned(Long reservationId) {
        Reservation reservation = reservationRepo.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (!owned.ownsProperty(reservation.getPropertyId(), orgId())) {
            // 남의 것은 없는 것과 같게 답한다.
            throw new ReservationNotFoundException(reservationId);
        }
        return reservation;
    }

    /** 숙소가 이 조직의 것이고 판매 단위가 그 숙소에 속하는지. */
    private void requireOwnedUnit(Long propertyId, Long unitId, Long orgId) {
        if (!owned.ownsProperty(propertyId, orgId)
                || !unitCatalog.unitIdsOf(propertyId).contains(unitId)) {
            // 숙소가 남의 것인지 판매 단위가 안 맞는지 구분해 알려 주지 않는다.
            throw new com.staysync.property.UnitNotFoundException(unitId);
        }
    }

    private List<Long> ownedPropertyIds() {
        return owned.propertyIdsOf(orgId());
    }

    private String guestNameOf(Reservation reservation) {
        if (reservation.getGuestId() == null) {
            return null;
        }
        return guestRepo.findById(reservation.getGuestId())
                .map(Guest::getName)
                .orElse(null);
    }

    private static Long orgId() {
        return AuthenticatedUser.current()
                .orElseThrow(() -> new IllegalStateException(
                        "인증이 필요한 경로인데 주체가 없다. SecurityConfig 설정을 확인할 것."))
                .orgId();
    }
}
