package com.mockota;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 시나리오에 넘기는 값. 전부 선택이고 빈 본문 {@code {}} 으로도 부를 수 있다.
 *
 * <p>기본값을 두는 이유는 테스트가 이 시나리오에서 <b>확인하려는 것</b>만 적게 하려는
 * 것이다. 순서 역전을 보려는 테스트가 요금과 인원수를 채워 넣고 있으면 무엇이 이
 * 시나리오의 요점인지가 묻힌다.
 *
 * @param body  게스트 메시지 본문. 메시지 시나리오만 쓴다
 * @param count 만들 건수. 시나리오마다 뜻이 다르다 — 중복은 같은 예약을 몇 번,
 *              다발은 동시에 몇 건, 순서 역전은 가장 높은 revision, 초과 판매는 몇 건,
 *              게스트 메시지는 몇 통(같은 식별자로 중복해 보낸다)
 */
public record ScenarioRequest(
        String bookingId,
        String roomId,
        LocalDate checkIn,
        LocalDate checkOut,
        String guestName,
        BigDecimal totalAmount,
        String body,
        Integer count) {

    public ScenarioRequest {
        if (bookingId == null || bookingId.isBlank()) {
            bookingId = "MOCK-" + Long.toString(System.nanoTime(), 36).toUpperCase();
        }
        if (roomId == null || roomId.isBlank()) {
            roomId = "room-1";
        }
        if (checkIn == null) {
            checkIn = LocalDate.now().plusDays(7);
        }
        if (checkOut == null) {
            checkOut = checkIn.plusDays(2);
        }
        if (guestName == null || guestName.isBlank()) {
            guestName = "Mock Guest";
        }
        if (totalAmount == null) {
            totalAmount = BigDecimal.valueOf(100_000);
        }
        if (body == null || body.isBlank()) {
            body = "체크인 시간을 조금 늦출 수 있을까요?";
        }
        if (count == null || count < 1) {
            count = 2;
        }
    }

    /** 빈 본문으로 부를 때. */
    public static ScenarioRequest defaults() {
        return new ScenarioRequest(null, null, null, null, null, null, null, null);
    }
}
