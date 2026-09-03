import { z } from 'zod';

/**
 * API 응답 스키마.
 *
 * 타입을 손으로 쓰지 않고 여기서 끌어낸다. 손으로 쓴 타입은 서버 응답과 어긋나도
 * 컴파일이 통과하고 런타임에야 드러난다. 스키마로 파싱하면 어긋나는 순간 그 자리에서
 * 실패해 어디가 틀렸는지 알 수 있다.
 */

/** 로그인·갱신·가입의 공통 응답. 리프레시 토큰은 여기 없다. 쿠키로 온다(ADR 0006). */
export const tokenResponseSchema = z.object({
  accessToken: z.string(),
  tokenType: z.string(),
  expiresInSeconds: z.number(),
});
export type TokenResponse = z.infer<typeof tokenResponseSchema>;

export const meResponseSchema = z.object({
  userId: z.number(),
  orgId: z.number(),
  email: z.string(),
  displayName: z.string(),
  role: z.enum(['OWNER', 'MANAGER', 'HOUSEKEEPER']),
});
export type MeResponse = z.infer<typeof meResponseSchema>;

export const propertySummarySchema = z.object({
  id: z.number(),
  name: z.string(),
  address: z.string().nullable(),
  timezone: z.string(),
  currency: z.string(),
  checkInTime: z.string(),
  checkOutTime: z.string(),
  status: z.string(),
});
export type PropertySummary = z.infer<typeof propertySummarySchema>;

/** 하루 한 칸. 백엔드 `CalendarGrid.DayCell` 과 짝이다. */
export const dayCellSchema = z.object({
  date: z.string(),
  avail: z.number(),
  price: z.number(),
  minStay: z.number(),
  stopSell: z.boolean(),
  /** P3 에서 채워진다. 지금은 항상 false 지만 화면은 이미 이 값을 보고 그린다. */
  conflict: z.boolean(),
});
export type DayCell = z.infer<typeof dayCellSchema>;

export const unitRowSchema = z.object({
  id: z.number(),
  name: z.string(),
  totalUnits: z.number(),
  days: z.array(dayCellSchema),
});
export type UnitRow = z.infer<typeof unitRowSchema>;

export const reservationBarSchema = z.object({
  id: z.number(),
  unitId: z.number(),
  checkIn: z.string(),
  checkOut: z.string(),
  /** 이름까지만 온다. 연락처는 응답에 없다(ADR 0007). */
  guestName: z.string().nullable(),
  channel: z.string(),
  status: z.string(),
  amount: z.number(),
});
export type ReservationBar = z.infer<typeof reservationBarSchema>;

/** 예약 단건 응답. 백엔드 `ReservationDtos.ReservationSummary` 와 짝이다. */
export const reservationSummarySchema = z.object({
  id: z.number(),
  propertyId: z.number(),
  unitId: z.number(),
  status: z.string(),
  checkIn: z.string(),
  checkOut: z.string(),
  nights: z.number(),
});
export type ReservationSummary = z.infer<typeof reservationSummarySchema>;

export const calendarGridSchema = z.object({
  from: z.string(),
  to: z.string(),
  units: z.array(unitRowSchema),
  reservations: z.array(reservationBarSchema),
});
export type CalendarGrid = z.infer<typeof calendarGridSchema>;
