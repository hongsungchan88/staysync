import { apiRequest } from './client';
import { reservationSummarySchema, type ReservationSummary } from './schemas';

/**
 * 예약 날짜를 옮긴다.
 *
 * 4주차 상태 머신의 `PATCH /api/reservations/{id}` 를 그대로 쓴다. 드래그 전용
 * 엔드포인트를 새로 만들지 않았다 — 같은 연산이라 두 벌이 되면 재고 처리 규칙이
 * 갈라진다. 인원을 넘기지 않으면 서버가 기존 값을 유지한다.
 *
 * **재고 판단은 서버가 한다.** 화면이 캐시에 있는 `avail` 을 보고 미리 막으면, 그
 * 사이에 다른 채널 예약이 들어온 경우를 놓친다. 옮길 수 있는지 아닌지는 응답으로만 안다.
 */
export async function moveReservation(
  reservationId: number,
  checkIn: string,
  checkOut: string,
): Promise<ReservationSummary> {
  const body = await apiRequest<unknown>(`/api/reservations/${reservationId}`, {
    method: 'PATCH',
    body: { checkIn, checkOut },
  });
  return reservationSummarySchema.parse(body);
}

export type Transition = 'check-in' | 'check-out';

/**
 * 체크인·체크아웃. 4주차 상태 머신의 전이를 화면에서 부르기만 한다(작업지시-15 2절 E).
 *
 * 전이가 되는지는 서버가 판정한다. CONFIRMED 가 아닌데 체크인하거나 CHECKED_IN 이
 * 아닌데 체크아웃하면 도메인이 거절하고, 그 이유가 그대로 화면에 뜬다. 체크아웃하면
 * 청소 태스크가 생기는 것은 Outbox 소비자(15주차)가 한다 — 여기서 부르지 않는다.
 */
export async function transitionReservation(
  reservationId: number,
  transition: Transition,
): Promise<ReservationSummary> {
  const body = await apiRequest<unknown>(`/api/reservations/${reservationId}/${transition}`, {
    method: 'POST',
  });
  return reservationSummarySchema.parse(body);
}
