import { z } from 'zod';
import type { ApiErrorBody } from './client';

/**
 * 직접예약 위젯이 쓰는 공개 API. 계획서 8.7.
 *
 * **`apiRequest` 를 쓰지 않는다.** 그쪽은 액세스 토큰을 싣고 401 이면 갱신을 시도한다.
 * 위젯에는 세션이 없으므로 갱신이 반드시 실패하고, 실패하면 `onSessionExpired` 가
 * 불려 **호스트의 다른 탭이 로그아웃된다.** 공개 경로는 자격 증명 없이 부르는 것이
 * 맞고, 그래서 여기만 `fetch` 를 직접 쓴다.
 *
 * 쿠키도 보내지 않는다(`credentials` 를 주지 않는다). 위젯은 iframe 안에서 남의
 * 페이지에 얹히므로 보낼 이유가 없다.
 */
export class PublicApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: ApiErrorBody | null,
  ) {
    super(body?.message ?? `요청이 실패했습니다. (HTTP ${status})`);
    this.name = 'PublicApiError';
  }
}

/** 하루 한 칸. 서버 `PublicBookingService.PublicDay` 와 짝이다. */
export const publicDaySchema = z.object({
  date: z.string(),
  available: z.number(),
  price: z.number(),
  minStay: z.number(),
  stopSell: z.boolean(),
});
export type PublicDay = z.infer<typeof publicDaySchema>;

export const publicUnitSchema = z.object({
  id: z.number(),
  name: z.string(),
  days: z.array(publicDaySchema).default([]),
});
export type PublicUnit = z.infer<typeof publicUnitSchema>;

/** 홀드 결과. 결제창이 이 금액으로 열린다. */
export const holdResultSchema = z.object({
  reservationId: z.number(),
  confirmationCode: z.string(),
  amount: z.number(),
  expiresAt: z.string(),
});
export type HoldResult = z.infer<typeof holdResultSchema>;

async function publicRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init);
  if (!response.ok) {
    throw new PublicApiError(response.status, await readErrorBody(response));
  }
  return (await response.json()) as T;
}

async function readErrorBody(response: Response): Promise<ApiErrorBody | null> {
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    // 오류 응답이 JSON 이 아닐 수 있다. 그 자체로 실패시키지는 않는다.
    return null;
  }
}

export async function fetchAvailability(
  propertyId: number,
  from: string,
  to: string,
): Promise<PublicUnit[]> {
  const query = new URLSearchParams({ from, to });
  const body = await publicRequest<unknown>(
    `/public/booking/${propertyId}/availability?${query}`,
  );
  return z.array(publicUnitSchema).parse(body);
}

/**
 * 위젯이 보내는 것.
 *
 * `quotedAmount` 는 **화면에 떠 있던 금액**이다. 서버가 이 값으로 예약을 만들지
 * 않는다 — 재계산과 대조하고 다르면 거절한다. 요금이 그 사이 바뀌었거나 값이
 * 조작됐다는 뜻이고, 어느 쪽이든 그 금액으로 결제창을 열면 안 된다.
 */
export interface HoldRequest {
  unitId: number;
  checkIn: string;
  checkOut: string;
  quotedAmount: number;
  adults: number;
  children: number;
  guestName: string;
  guestPhone?: string;
  guestEmail?: string;
}

export async function createHold(
  propertyId: number,
  request: HoldRequest,
): Promise<HoldResult> {
  const body = await publicRequest<unknown>(`/public/booking/${propertyId}/hold`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  return holdResultSchema.parse(body);
}
