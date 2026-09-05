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
  // 서버가 `non_null` 로 직렬화하므로 값이 없는 필드는 **키가 아예 없다.**
  // `nullable` 은 null 은 받아도 없는 키는 거절해서, 주소 없는 숙소 하나가 캘린더
  // 화면 전체를 오류로 만든다. 파싱이 통째로 실패하기 때문이다.
  address: z.string().nullish(),
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
  /** 이름까지만 온다. 연락처는 응답에 없다(ADR 0007). 없으면 키가 빠진다. */
  guestName: z.string().nullish(),
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

/** 일괄 편집 결과. 백엔드 `BulkEdit.Result` 와 짝이다. */
export const bulkEditResultSchema = z.object({
  unitCount: z.number(),
  dayCount: z.number(),
  cellCount: z.number(),
  changed: z.array(z.string()),
  dryRun: z.boolean(),
});
export type BulkEditResult = z.infer<typeof bulkEditResultSchema>;

// --- 채널 (P3 10주차) ------------------------------------------------------

/**
 * 어댑터가 지원하는 기능. 백엔드 `channel.port.Capability` 와 짝이다.
 *
 * 서버가 새 값을 추가하면 이 배열에 없는 문자열이 온다. `z.enum` 으로 막으면 파싱이
 * 통째로 실패해 화면이 죽으므로 문자열로 받고 표시할 때만 이름을 붙인다.
 */
export const capabilitySchema = z.string();
export type Capability = z.infer<typeof capabilitySchema>;

export const adapterTypeSchema = z.enum(['ICAL', 'CHANNEX', 'MOCK']);
export type AdapterType = z.infer<typeof adapterTypeSchema>;

/**
 * 채널 연결. 백엔드 `ChannelDtos.ConnectionResponse` 와 짝이다.
 *
 * `credentials` 는 **언제나 마스킹된 값**이다(`chnx••••1a2b`). 서버가 평문을 내보내지
 * 않으므로 화면도 평문을 다룰 일이 없다. 수정할 때는 새 값을 입력받고, 비워 두면
 * 서버가 기존 값을 유지한다.
 */
export const channelConnectionSchema = z.object({
  id: z.number(),
  propertyId: z.number(),
  channelCode: z.string(),
  adapterType: adapterTypeSchema,
  displayName: z.string().nullish(),
  syncEnabled: z.boolean(),
  credentials: z.record(z.string()).default({}),
  capabilities: z.array(capabilitySchema).default([]),
});
export type ChannelConnection = z.infer<typeof channelConnectionSchema>;

export const channelMappingSchema = z.object({
  id: z.number(),
  unitId: z.number(),
  externalUnitId: z.string(),
  externalRateId: z.string().nullish(),
});
export type ChannelMapping = z.infer<typeof channelMappingSchema>;

/**
 * 매핑 화면 한 장.
 *
 * 서버가 `non_null` 로 직렬화하므로 **매핑되지 않은 단위에는 `mapping` 키가 아예 없다.**
 * `nullish` 로 받는 이유다. 그 단위는 이 채널에 나가지 않는다.
 */
export const mappingBoardSchema = z.object({
  connection: channelConnectionSchema,
  units: z.array(
    z.object({
      unitId: z.number(),
      unitName: z.string(),
      mapping: channelMappingSchema.nullish(),
    }),
  ),
});
export type MappingBoard = z.infer<typeof mappingBoardSchema>;
