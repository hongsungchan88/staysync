package com.staysync.booking;

import com.staysync.booking.calendar.CalendarGrid;
import com.staysync.booking.calendar.CalendarService;
import com.staysync.booking.domain.Guest;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.property.OwnedResources;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직접예약 위젯이 쓰는 공개 경로. 계획서 8.7.
 *
 * <p><b>{@code OwnedResources} 를 거치지 않는 유일한 쓰기 경로다.</b> 로그인이 없으므로
 * 조직으로 좁힐 수가 없다. 그래서 규칙이 하나 더 붙는다 — <b>숙소 식별자 외에는 아무것도
 * 클라이언트를 믿지 않는다.</b> 가용도 요금도 금액도 서버가 다시 구한다.
 *
 * <p><b>예약 막대를 내보내지 않는다.</b> {@link CalendarService} 가 돌려주는
 * {@code CalendarGrid} 에는 게스트 이름이 실린 막대가 들어 있다. 조립은 재사용하되
 * 공개 응답에는 날짜별 수량과 요금만 옮긴다 — 그 경계를 여기 한 곳에 둔다.
 */
@Service
public class PublicBookingService {

    private static final Logger log = LoggerFactory.getLogger(PublicBookingService.class);

    /** 한 번에 조회할 수 있는 최대 기간. 공개 경로라 무한정 넓은 조회를 열어 두지 않는다. */
    private static final int MAX_DAYS = 180;

    private final CalendarService calendar;
    private final BookingService booking;
    private final GuestRegistrar guests;
    private final OwnedResources owned;

    PublicBookingService(CalendarService calendar, BookingService booking,
                         GuestRegistrar guests, OwnedResources owned) {
        this.calendar = calendar;
        this.booking = booking;
        this.guests = guests;
        this.owned = owned;
    }

    /** 위젯이 그릴 날짜 한 칸. 게스트 정보도 예약도 담기지 않는다. */
    public record PublicDay(LocalDate date, int available, BigDecimal price,
                            short minStay, boolean stopSell) {

        /** 팔 수 있는 날인지. 수량이 있어도 판매중지면 팔지 않는다. */
        boolean sellable() {
            return available > 0 && !stopSell;
        }
    }

    public record PublicUnit(Long id, String name, List<PublicDay> days) {
    }

    /**
     * 위젯이 그릴 것 전부.
     *
     * <p><b>숙소 이름을 함께 싣는다.</b> 공개 페이지라 손님이 어느 숙소를 예약하는지
     * 화면에서 확인할 수 있어야 한다 — 남의 사이트에 iframe 으로 얹히면 주변 맥락이
     * 사라지므로 위젯 스스로 밝혀야 한다.
     */
    public record PublicAvailability(String propertyName, List<PublicUnit> units) {
    }

    /** 홀드 결과. 결제창이 이 값으로 열린다. */
    public record HoldResult(Long reservationId, String confirmationCode,
                             BigDecimal amount, java.time.OffsetDateTime expiresAt) {
    }

    // --- 조회 -----------------------------------------------------------------

    /**
     * 그 숙소의 가용과 요금.
     *
     * <p>숙소가 없으면 {@code PropertyNotFoundException} 이 올라간다. 공개 경로에서
     * 없는 숙소와 남의 숙소를 구분해 알려 줄 이유가 없으므로 둘 다 404 다.
     */
    @Transactional(readOnly = true)
    public PublicAvailability availability(Long propertyId, LocalDate from, LocalDate to) {
        requireRange(from, to);
        // 숙소가 실재하는지 확인한다. 조직은 묻지 않는다 — 공개 경로라 주체가 없다.
        String propertyName = owned.propertyNameOf(propertyId)
                .orElseThrow(() -> new com.staysync.property.PropertyNotFoundException(propertyId));

        CalendarGrid grid = calendar.assemble(propertyId, from, to);

        List<PublicUnit> units = new ArrayList<>();
        for (CalendarGrid.UnitRow row : grid.units()) {
            List<PublicDay> days = new ArrayList<>(row.days().size());
            for (CalendarGrid.DayCell cell : row.days()) {
                // 여기가 공개 경계다. cell 의 conflict 도 내보내지 않는다 —
                // 우리 운영 사정이고 게스트가 알 것이 아니다.
                days.add(new PublicDay(cell.date(), cell.avail(), cell.price(),
                        cell.minStay(), cell.stopSell()));
            }
            units.add(new PublicUnit(row.id(), row.name(), days));
        }
        return new PublicAvailability(propertyName, units);
    }

    /**
     * 그 기간의 요금 합계. <b>서버가 구하는 값이고 이것만 신뢰한다.</b>
     *
     * <p>체크아웃일은 숙박하지 않으므로 마지막 밤까지 더한다({@code StayPeriod} 가
     * 배타적이다).
     */
    @Transactional(readOnly = true)
    public BigDecimal quote(Long propertyId, Long unitId, StayPeriod period) {
        List<PublicDay> nights = nightsOf(propertyId, unitId, period);
        BigDecimal total = BigDecimal.ZERO;
        for (PublicDay night : nights) {
            total = total.add(night.price());
        }
        return total;
    }

    // --- 홀드 -----------------------------------------------------------------

    /**
     * 임시 점유를 만든다. 결제 전 단계다.
     *
     * <p>거르는 것이 셋이다. 셋 다 <b>클라이언트를 믿으면 팔 수 없는 것을 파는</b>
     * 경우다.
     *
     * <ol>
     *   <li><b>팔 수 없는 날</b> — 수량이 없거나 판매중지인 날이 하루라도 있으면 막는다</li>
     *   <li><b>최소 숙박일</b> — 요금 캘린더가 정한 값보다 짧으면 막는다</li>
     *   <li><b>금액 불일치</b> — 화면이 보낸 값과 서버 재계산이 다르면 막는다.
     *       화면에 뜬 값으로 결제창이 열리므로, 어긋난 채 진행하면 <b>결제 금액과
     *       예약 금액이 다른 예약</b>이 생긴다</li>
     * </ol>
     *
     * <p>재고 확보 자체는 {@link BookingService#hold} 가 락과 원장으로 다시 막는다.
     * 여기 확인은 사용자에게 이유를 알려 주기 위한 것이고, 최종 방어선이 아니다 —
     * 확인과 확보 사이에 다른 요청이 끼어들 수 있다.
     */
    public HoldResult hold(Long propertyId, Long unitId, StayPeriod period,
                           BigDecimal quotedAmount, short adults, short children,
                           String guestName, String guestPhone, String guestEmail) {
        List<PublicDay> nights = nightsOf(propertyId, unitId, period);

        for (PublicDay night : nights) {
            if (!night.sellable()) {
                throw new UnavailableDateException(night.date());
            }
        }
        short minStay = nights.get(0).minStay();
        if (nights.size() < minStay) {
            throw new MinimumStayException(minStay, nights.size());
        }

        BigDecimal amount = quote(propertyId, unitId, period);
        if (quotedAmount != null && quotedAmount.compareTo(amount) != 0) {
            // 화면이 낡은 요금을 들고 있었거나 값이 조작됐다. 어느 쪽이든 그 값으로
            // 결제창을 열면 안 된다.
            log.warn("위젯이 보낸 금액이 서버 재계산과 다르다. propertyId={} 보낸값={} 서버값={}",
                    propertyId, quotedAmount, amount);
            throw new QuoteMismatchException(quotedAmount, amount);
        }

        Long guestId = registerGuest(propertyId, guestName, guestPhone, guestEmail);
        // 인원을 그대로 넘긴다. 넘기지 않으면 예약 기본값(성인 2)이 박혀 호스트가 보는
        // 인원이 늘 2명이 된다 — 청소와 정원 판단이 거기에 걸린다.
        Reservation held = booking.hold(propertyId, unitId, period, amount, guestId,
                adults, children);

        log.info("직접예약 홀드를 만들었다. reservationId={} propertyId={} unitId={} 금액={}",
                held.getId(), propertyId, unitId, amount);
        return new HoldResult(held.getId(), held.getConfirmationCode(), amount,
                held.getHoldExpiresAt());
    }

    // --- 안쪽 -----------------------------------------------------------------

    /**
     * 그 판매 단위의 숙박일별 값.
     *
     * <p>판매 단위가 이 숙소의 것인지도 여기서 갈린다. 공개 경로라 클라이언트가 남의
     * 판매 단위 식별자를 보낼 수 있고, 숙소 안에 없으면 조회 결과가 비어 있다.
     */
    private List<PublicDay> nightsOf(Long propertyId, Long unitId, StayPeriod period) {
        // 마지막 밤까지. 체크아웃일은 재고를 차지하지 않는다.
        LocalDate lastNight = period.checkOut().minusDays(1);
        List<PublicUnit> units = availability(propertyId, period.checkIn(), lastNight).units();

        return units.stream()
                .filter(unit -> unit.id().equals(unitId))
                .findFirst()
                .map(PublicUnit::days)
                .orElseThrow(() -> new com.staysync.property.UnitNotFoundException(unitId));
    }

    /**
     * 게스트를 만든다. <b>연락처는 여기서만 평문을 만진다.</b>
     *
     * <p>{@link GuestRegistrar} 가 암호화와 검색 해시를 함께 만든다. 위젯이 평문을
     * 보내는 유일한 자리이므로 다른 경로로 새지 않게 이 메서드 밖으로 값을 넘기지
     * 않는다(ADR 0007).
     */
    private Long registerGuest(Long propertyId, String name, String phone, String email) {
        if (name == null || name.isBlank()) {
            // 이름 없이 만들면 {{guestName}} 템플릿이 나갈 수 없고, 호스트가 누구의
            // 예약인지 알 방법도 없다.
            throw new InvalidPublicBookingException("예약자 이름이 필요합니다.");
        }
        Long orgId = owned.orgIdOfProperty(propertyId)
                .orElseThrow(() -> new com.staysync.property.PropertyNotFoundException(propertyId));
        Guest guest = guests.register(orgId, name.trim(), phone, email);
        return guest.getId();
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new InvalidPublicBookingException(
                    "기간이 올바르지 않습니다. from=%s to=%s".formatted(from, to));
        }
        if (from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new InvalidPublicBookingException(
                    "한 번에 조회할 수 있는 기간은 %d일까지입니다.".formatted(MAX_DAYS));
        }
    }
}
