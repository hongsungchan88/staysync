package com.staysync.booking.calendar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 캘린더 그리드 응답. 계획서 13.5 가 모양의 명세다.
 *
 * <p>프론트엔드가 곧바로 렌더링할 수 있게 정규화한다. 날짜별 셀이 판매 단위마다
 * 채워져 있어 화면이 빈 칸을 따로 계산하지 않는다.
 *
 * <p><b>연락처를 담지 않는다.</b> {@code guestName} 까지는 담는다. {@code guest.name} 은
 * 평문 컬럼이라 새로 새는 것이 없다. 전화번호와 이메일은 담지 않는다. ADR 0007 의 결정과
 * 이벤트 페이로드에서 그은 선을 화면 응답에도 그대로 적용한다. 연락처가 필요해지는
 * 순간은 예약 상세이고 그건 이 API 가 아니다.
 */
public record CalendarGrid(
        LocalDate from,
        LocalDate to,
        List<UnitRow> units,
        List<ReservationBar> reservations) {

    /** 판매 단위 한 줄. */
    public record UnitRow(Long id, String name, short totalUnits, List<DayCell> days) {
    }

    /**
     * 하루 한 칸.
     *
     * @param avail    남은 수량. 원장 행이 없으면 {@code totalUnits} 그대로다
     * @param price    요금. {@code rate_calendar} 에 없으면 {@code unit.base_price}
     * @param minStay  최소 숙박일. 출처는 {@code rate_calendar}
     * @param stopSell 판매중지. 출처는 {@code inventory_ledger} 다. minStay 와 다르다
     * @param conflict 중복예약 충돌. 지금은 항상 false. P3 에서 채워진다
     */
    public record DayCell(
            LocalDate date,
            int avail,
            BigDecimal price,
            short minStay,
            boolean stopSell,
            boolean conflict) {
    }

    /**
     * 예약 막대.
     *
     * <p>계획서 13.5 예시에는 {@code roomId} 가 있지만 담지 않는다. 데이터 모델을
     * 2계층으로 줄이면서 호실 개념을 없앴기 때문이다(결정문서 01). 화면에도 배방이 없다.
     */
    public record ReservationBar(
            Long id,
            Long unitId,
            LocalDate checkIn,
            LocalDate checkOut,
            String guestName,
            String channel,
            String status,
            BigDecimal amount) {
    }
}
