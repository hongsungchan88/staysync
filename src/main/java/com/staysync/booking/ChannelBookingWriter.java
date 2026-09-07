package com.staysync.booking;

import com.staysync.booking.domain.InsufficientInventoryException;
import com.staysync.booking.domain.OverbookingConflict;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.ReservationStatus;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.property.OwnedResources;
import com.staysync.shared.audit.ActorKind;
import com.staysync.shared.audit.AuditRecorder;
import com.staysync.shared.outbox.OutboxRecorder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채널 예약 수신의 <b>트랜잭션 경계</b>. 계획서 13.2 의 {@code BookingIngestService} 다.
 *
 * <p>예약 저장, 재고 변경, 충돌 기록, {@code outbox_event}, {@code audit_log} 가
 * <b>한 트랜잭션</b>이다. 판별 기준은 이 프로젝트에서 쓰던 그것이다 — 바깥이 롤백될 때
 * 이 쓰기가 남아야 하는가, 사라져야 하는가. 여기는 전부 "사라져야 한다"이다. 예약만
 * 남고 재고가 안 줄면 캘린더가 거짓말을 하고, 충돌만 남고 예약이 없으면 운영자가
 * 없는 예약을 푼다.
 *
 * <p>락은 {@link ChannelBookingIngestService} 가 이 빈을 부르기 <i>전에</i> 잡는다.
 * 순서가 반대면 트랜잭션이 열린 채 락을 기다려 커넥션 풀이 마른다.
 *
 * <p>판단 순서는 계획서 13.2 그대로다.
 *
 * <ol>
 *   <li>멱등성 — {@code (channel_code, channel_booking_id)} 로 찾는다</li>
 *   <li>재고 — {@link InventoryService} 를 통한다. 락은 날짜 오름차순</li>
 *   <li><b>부족해도 거절하지 않는다</b> — 받아들이고 충돌로 기록한다</li>
 *   <li>이벤트와 감사 — 도메인 변경과 한 트랜잭션</li>
 * </ol>
 */
@Component
class ChannelBookingWriter {

    private static final Logger log = LoggerFactory.getLogger(ChannelBookingWriter.class);

    /** 예약 한 건이 차지하는 재고 수량. {@code ReservationWriter} 와 같은 전제다. */
    private static final int UNITS = 1;

    /** 그날 겹치는 예약을 찾을 때 세는 상태. 취소·만료는 재고를 차지하지 않는다. */
    private static final List<ReservationStatus> ACTIVE = List.of(
            ReservationStatus.HOLD, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN);

    private final ReservationRepository reservationRepo;
    private final ReservationWriter reservationWriter;
    private final InventoryService inventory;
    private final OverbookingConflictRepository conflicts;
    private final BookingService bookingService;
    private final OutboxRecorder outbox;
    private final AuditRecorder audit;
    private final GuestRegistrar guests;
    private final OwnedResources owned;

    ChannelBookingWriter(ReservationRepository reservationRepo,
                         ReservationWriter reservationWriter,
                         InventoryService inventory,
                         OverbookingConflictRepository conflicts,
                         BookingService bookingService,
                         OutboxRecorder outbox,
                         AuditRecorder audit,
                         GuestRegistrar guests,
                         OwnedResources owned) {
        this.reservationRepo = reservationRepo;
        this.reservationWriter = reservationWriter;
        this.inventory = inventory;
        this.conflicts = conflicts;
        this.bookingService = bookingService;
        this.outbox = outbox;
        this.audit = audit;
        this.guests = guests;
        this.owned = owned;
    }

    @Transactional
    ChannelBookingResult ingest(ChannelBookingCommand command) {
        Optional<Reservation> existing = reservationRepo.findByChannelCodeAndChannelBookingId(
                command.channelCode(), command.channelBookingId());

        if (existing.isPresent()) {
            return applyToExisting(existing.get(), command);
        }
        if (command.cancellation()) {
            // 모르는 예약의 취소다. 만들었다가 지우는 것보다 넘기는 편이 낫다 —
            // 취소된 예약을 만들면 재고를 잡았다 푸는 흔적만 남는다.
            log.info("모르는 예약의 취소 통지라 넘긴다. channel={} bookingId={}",
                    command.channelCode(), command.channelBookingId());
            return ChannelBookingResult.of(ChannelBookingResult.Outcome.DUPLICATE, null);
        }
        return create(command);
    }

    // --- 이미 아는 예약 ---------------------------------------------------------

    private ChannelBookingResult applyToExisting(Reservation reservation,
                                                 ChannelBookingCommand command) {
        if (command.cancellation()) {
            // 취소는 revision 을 따지지 않는다. 채널이 취소했다는 것이 최종 사실이고,
            // 여기서 버전으로 막으면 취소된 예약이 살아남아 팔 수 없는 방을 잡는다.
            reservationWriter.cancel(reservation.getId());
            return ChannelBookingResult.of(
                    ChannelBookingResult.Outcome.CANCELLED, reservation.getId());
        }

        StayPeriod oldPeriod = reservation.getPeriod();
        if (!applyIncoming(reservation, command)) {
            // 낮은 버전이 나중에 도착했거나(버전이 있는 채널), 값이 그대로다(없는 채널).
            // 전달 순서가 뒤바뀌는 것은 정상이고, 여기서 반영하면 옛 날짜로 되돌아간다.
            log.debug("반영할 것이 없어 무시한다. bookingId={} incoming={} current={}",
                    command.channelBookingId(), command.revision(), reservation.getRevision());
            return ChannelBookingResult.of(
                    ChannelBookingResult.Outcome.DUPLICATE, reservation.getId());
        }

        List<LocalDate> conflicted = List.of();
        boolean datesChanged = !oldPeriod.checkIn().equals(command.period().checkIn())
                || !oldPeriod.checkOut().equals(command.period().checkOut());
        if (datesChanged && reservation.isActive()) {
            // 반드시 반납이 먼저다. 두 기간이 겹칠 때 새 기간을 먼저 잡으려 하면
            // 겹치는 날짜에 같은 예약이 두 자리를 차지한다({@code changeStay} 와 같다).
            inventory.release(reservation.getUnitId(), oldPeriod, UNITS);
            conflicted = reserveOrForce(reservation, command);
        }

        outbox.record("RESERVATION", reservation.getId(), "RESERVATION_DATES_CHANGED",
                channelPayload(reservation, command));
        // Map.of 를 쓰지 않는다. 버전이 없는 채널(iCal)은 revision 이 null 이고
        // Map.of 는 null 을 거부한다 — 수신 전체가 NPE 로 실패하고, 폴링이 그걸
        // 잡아 로그만 남기므로 예약이 조용히 갱신되지 않는다.
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("checkIn", command.period().checkIn().toString());
        after.put("checkOut", command.period().checkOut().toString());
        after.put("revision", command.revision());
        audit.recordAs(ActorKind.CHANNEL, null, "RESERVATION", reservation.getId(),
                "CHANNEL_REVISION",
                Map.of("checkIn", oldPeriod.checkIn().toString(),
                        "checkOut", oldPeriod.checkOut().toString()),
                after);

        return conflicted.isEmpty()
                ? ChannelBookingResult.of(ChannelBookingResult.Outcome.UPDATED, reservation.getId())
                : ChannelBookingResult.conflict(reservation.getId(), conflicted);
    }

    /**
     * 발행물에서 사라진 예약을 취소한다. 스냅샷 채널(iCal)만 이 경로를 쓴다.
     *
     * <p>{@code cancel} 은 멱등하고 재고 반납까지 한 트랜잭션이다. 여기서 따로
     * 원장을 건드리지 않는 이유가 그것이다.
     *
     * <p>부르는 쪽이 대량 소실 방어를 먼저 통과시킨다. 파싱이 실패했거나 발행자가
     * 잠깐 빈 달력을 낸 주기에 이 메서드가 돌면 <b>그 채널의 예약이 전부 사라진다.</b>
     */
    @Transactional
    int cancelMissing(Long unitId, String channelCode, java.util.Set<String> present) {
        int cancelled = 0;
        for (Reservation reservation : reservationRepo.findActiveOfChannel(unitId, channelCode)) {
            if (present.contains(reservation.getChannelBookingId())) {
                continue;
            }
            reservationWriter.cancel(reservation.getId());
            log.info("발행물에서 사라진 예약을 취소했다. channel={} bookingId={} reservationId={}",
                    channelCode, reservation.getChannelBookingId(), reservation.getId());
            cancelled++;
        }
        return cancelled;
    }

    /**
     * 들어온 값을 반영할지 정한다.
     *
     * <p><b>버전이 없는 채널은 크기를 비교하지 않는다.</b> iCal 이 그렇고, 계획서
     * 13.4 처럼 내용 해시를 버전 자리에 넣으면 해시에 순서가 없어서 날짜가 바뀐 뒤의
     * 해시가 우연히 작을 때 수정이 조용히 무시된다. 그런 채널은 매번 전체 스냅샷을
     * 받으므로 순서 역전이 없고, 값이 다르면 그것이 곧 수정이다. 근거는 ADR 0013.
     */
    private static boolean applyIncoming(Reservation reservation, ChannelBookingCommand command) {
        return command.revision() == null
                ? reservation.applyValues(command.period(), command.totalAmount())
                : reservation.applyRevision(command.revision(), command.period(), command.totalAmount());
    }

    // --- 새 예약 ---------------------------------------------------------------

    private ChannelBookingResult create(ChannelBookingCommand command) {
        Reservation reservation = Reservation.fromChannel(
                command.propertyId(), command.unitId(), command.period(),
                command.channelCode(), command.channelBookingId(),
                bookingService.uniqueCode(),
                // 버전이 없는 채널은 0 에서 시작한다. 이후 판정도 크기를 보지 않는다.
                command.revision() == null ? 0 : command.revision(),
                command.totalAmount(), BigDecimal.ZERO);

        reservation.assignGuest(guestIdOf(command));

        // 재고를 잡기 전에 저장한다. 초과 판매 경로에서 충돌 기록이 예약 식별자를
        // 필요로 하기 때문이다.
        Reservation saved = reservationRepo.saveAndFlush(reservation);
        List<LocalDate> conflicted = reserveOrForce(saved, command);

        outbox.record("RESERVATION", saved.getId(), "RESERVATION_CONFIRMED",
                channelPayload(saved, command));
        // 채널 수신은 SecurityContext 로 표현할 수 없는 주체다. 5~6주차에 AuditRecorder
        // 에 recordAs 를 열어 둔 자리가 여기다.
        audit.recordAs(ActorKind.CHANNEL, null, "RESERVATION", saved.getId(),
                "CHANNEL_RECEIVE", null,
                Map.of("channelCode", command.channelCode(),
                        "status", saved.getStatus().name(),
                        "checkIn", command.period().checkIn().toString(),
                        "checkOut", command.period().checkOut().toString()));

        return conflicted.isEmpty()
                ? ChannelBookingResult.of(ChannelBookingResult.Outcome.CREATED, saved.getId())
                : ChannelBookingResult.conflict(saved.getId(), conflicted);
    }

    /**
     * 재고를 잡되, <b>모자라면 거절하지 않고 넘겨 기록한다.</b>
     *
     * <p>이 메서드가 이 주의 핵심이다. 거절해 버려도 우리 쪽 로그는 깨끗하고, 문제가
     * 드러나는 것은 게스트가 도착한 뒤다.
     *
     * <p><b>먼저 보고 나서 고른다. {@code reserve} 를 시도했다가 예외를 잡는 방식이
     * 아니다.</b> {@code InventoryLedgerWriter.apply} 가 {@code @Transactional} 이라
     * {@link InsufficientInventoryException} 이 그 경계를 넘는 순간 스프링이 지금
     * 트랜잭션을 rollback-only 로 찍는다. 예외를 잡아 계속 진행해도 커밋 시점에
     * {@code UnexpectedRollbackException} 이 나고, <b>예약도 충돌 기록도 전부
     * 사라진다.</b> 거절하지 않겠다는 결정이 조용히 뒤집히는 자리였다.
     *
     * <p>판매 단위 락을 이미 쥐고 있으므로 보는 것과 잡는 것 사이에 재고가 바뀌지
     * 않는다. 확인과 실행이 갈릴 걱정은 락이 없앤다.
     *
     * @return 재고를 넘긴 날짜들. 비어 있으면 정상적으로 잡혔다
     */
    /**
     * 채널이 알려 준 이름으로 게스트를 만든다. P4 15주차에 더했다.
     *
     * <p><b>이름이 없으면 만들지 않는다.</b> iCal 발행물에는 이름이 없고(조사-02),
     * 빈 게스트를 만들면 {@code {{guestName}}} 이 빈칸으로 치환되어 "안녕하세요 님"
     * 이 나간다. 그 경우에는 게스트를 붙이지 않는 편이 낫다 — 치환할 값이 없으면
     * 템플릿이 발송을 막는다(14주차 결정).
     *
     * <p><b>연락처는 담지 않는다.</b> 채널이 주지도 않고, 받으면 암호화 경계를
     * 우회하는 두 번째 입구가 된다(ADR 0007). 이름만 있는 게스트가 된다.
     *
     * <p>같은 이름을 다시 만드는 것을 막지 않는다. 채널 예약마다 게스트 한 건이고,
     * 이름만으로 동일인을 판정하면 다른 사람의 대화가 하나로 합쳐진다.
     */
    private Long guestIdOf(ChannelBookingCommand command) {
        if (command.guestName() == null || command.guestName().isBlank()) {
            return null;
        }
        return owned.orgIdOfProperty(command.propertyId())
                .map(orgId -> guests.register(orgId, command.guestName(), null, null).getId())
                .orElse(null);
    }

    private List<LocalDate> reserveOrForce(Reservation reservation, ChannelBookingCommand command) {
        List<LocalDate> shortDates = shortDatesOf(reservation.getUnitId(), command.period());
        if (shortDates.isEmpty()) {
            inventory.reserve(reservation.getUnitId(), command.period(), UNITS);
            return List.of();
        }
        inventory.forceBook(reservation.getUnitId(), command.period(), UNITS);
        raiseConflicts(reservation, shortDates);
        log.warn("재고를 넘겨 채널 예약을 받아들였다. reservationId={} channel={} 날짜={}",
                reservation.getId(), command.channelCode(), shortDates);
        return shortDates;
    }

    /**
     * 재고가 모자란 밤을 전부 찾는다.
     *
     * <p>{@code forceBook} 이 {@code totalUnits} 를 올려 제약을 만족시키므로,
     * <b>반드시 그 전에</b> 읽어야 한다. 뒤에 읽으면 전부 정상으로 보인다.
     *
     * <p>판단 규칙은 {@code InventoryLedger.ensureAvailable} 과 같아야 한다 —
     * 판매중지이거나 남은 수량이 모자라면 못 잡는다. 갈리면 {@code reserve} 가
     * 예외를 던져 수신 전체가 실패하므로, 조용히 어긋나지는 않는다.
     */
    private List<LocalDate> shortDatesOf(Long unitId, StayPeriod period) {
        LocalDate lastNight = period.checkOut().minusDays(1);
        short capacity = inventory.capacityOf(unitId);
        List<LocalDate> shortDates = new ArrayList<>();
        for (DailyAvailability day : inventory.availabilityByDate(
                unitId, period.checkIn(), lastNight, capacity)) {
            if (day.available() < UNITS || day.stopSell()) {
                shortDates.add(day.date());
            }
        }
        return shortDates;
    }

    private void raiseConflicts(Reservation reservation, List<LocalDate> dates) {
        for (LocalDate date : dates) {
            conflicts.save(new OverbookingConflict(
                    reservation.getPropertyId(), reservation.getUnitId(), date,
                    overlappingIds(reservation, date)));
        }
    }

    /** 그날 겹치는 예약 전부. 상대가 없는 "충돌"은 13주차에 풀 수 없다. */
    private List<Long> overlappingIds(Reservation reservation, LocalDate date) {
        List<Long> ids = new ArrayList<>();
        for (Reservation other : reservationRepo.findOverlapping(
                reservation.getPropertyId(), date, date.plusDays(1), ACTIVE)) {
            if (other.getUnitId().equals(reservation.getUnitId())) {
                ids.add(other.getId());
            }
        }
        if (!ids.contains(reservation.getId())) {
            // 방금 저장한 예약이 조회에 안 잡히는 경우를 대비한다. 충돌 기록에
            // 정작 원인이 빠지면 안 된다.
            ids.add(reservation.getId());
        }
        return ids;
    }

    /**
     * 채널 예약의 이벤트 페이로드.
     *
     * <p>{@code ReservationEvents.payloadOf} 와 같은 모양이어야 한다. 채널 전파
     * 소비자가 {@code unitId}·{@code checkIn}·{@code checkOut}·{@code channelCode} 를
     * 읽어 다른 채널로 재고를 밀어낸다.
     *
     * <p>이름과 연락처는 담지 않는다. ADR 0007 의 선이 그대로다.
     */
    private static Map<String, Object> channelPayload(Reservation reservation,
                                                      ChannelBookingCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", reservation.getId());
        payload.put("propertyId", reservation.getPropertyId());
        payload.put("unitId", reservation.getUnitId());
        payload.put("confirmationCode", reservation.getConfirmationCode());
        payload.put("channelCode", command.channelCode());
        payload.put("status", reservation.getStatus().name());
        payload.put("checkIn", reservation.getPeriod().checkIn().toString());
        payload.put("checkOut", reservation.getPeriod().checkOut().toString());
        payload.put("totalAmount", reservation.getTotalAmount());
        return payload;
    }
}
