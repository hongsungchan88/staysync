package com.staysync.booking;

import java.time.LocalDate;

/**
 * 하루치 판매 가능 수량. booking 이 바깥에 내보내는 값 타입이다.
 *
 * <p>{@code booking.domain.InventoryLedger} 를 그대로 넘기지 않는다. 그건 모듈 내부
 * 구현이라 다른 모듈이 참조하면 {@code ModularityTest} 가 깨진다.
 *
 * <p>채널 전파(P3 12주차)가 쓴다. 날짜마다 값이 다르므로 기간 최솟값 하나로는 부족하다 —
 * 최솟값을 전 기간에 보내면 여유 있는 날까지 막혀 팔 수 있는 방을 못 판다.
 *
 * @param available 남은 수량. 원장 행이 없는 날은 {@code totalUnits} 그대로다
 * @param stopSell  판매중지. 수량과 별개다. 재고가 있어도 팔지 않는 날이 있다
 */
public record DailyAvailability(LocalDate date, int available, boolean stopSell) {
}
