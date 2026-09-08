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

/**
 * 중복예약 충돌 하나. 계획서 7.4 이고 방어 4계층의 마지막 자리다.
 *
 * **게스트 이름과 연락처가 없다.** 운영자가 누구를 옮길지 고르는 데 필요한 것은
 * 예약번호와 채널, 날짜다. 서버가 담지 않으므로 화면도 보여 줄 수 없다.
 *
 * `resolution` 과 `resolvedAt` 은 해소한 뒤에만 있다(`non_null` 직렬화).
 */
export const conflictReservationSchema = z.object({
  id: z.number(),
  unitId: z.number(),
  confirmationCode: z.string(),
  channelCode: z.string().nullish(),
  status: z.string(),
  checkIn: z.string(),
  checkOut: z.string(),
});
export type ConflictReservation = z.infer<typeof conflictReservationSchema>;

export const conflictSchema = z.object({
  id: z.number(),
  propertyId: z.number(),
  unitId: z.number(),
  stayDate: z.string(),
  severity: z.string(),
  status: z.string(),
  resolution: z.string().nullish(),
  detectedAt: z.string().nullish(),
  resolvedAt: z.string().nullish(),
  reservations: z.array(conflictReservationSchema).default([]),
});
export type Conflict = z.infer<typeof conflictSchema>;

/**
 * 인박스. 계획서 8.5.
 *
 * **연락처가 없다.** 게스트 이름까지만 온다 — 인박스가 누구와의 대화인지 보여 줘야
 * 하고 템플릿이 그 값을 쓴다. 전화번호와 이메일은 서버가 담지 않는다(ADR 0007).
 *
 * 예약이 붙지 않은 스레드가 있다. 채널이 예약보다 메시지를 먼저 보내는 경우이고,
 * 그때는 예약 관련 필드의 키가 아예 빠진다(`non_null` 직렬화) — `nullish` 로 받는다.
 */
export const threadSummarySchema = z.object({
  id: z.number(),
  channelCode: z.string(),
  subject: z.string().nullish(),
  unreadCount: z.number(),
  lastMessageAt: z.string().nullish(),
  reservationId: z.number().nullish(),
  confirmationCode: z.string().nullish(),
  guestName: z.string().nullish(),
  checkIn: z.string().nullish(),
  checkOut: z.string().nullish(),
  reservationStatus: z.string().nullish(),
});
export type ThreadSummary = z.infer<typeof threadSummarySchema>;

export const messageSchema = z.object({
  id: z.number(),
  direction: z.string(),
  /** GUEST | HOST | AI | SYSTEM. SYSTEM 은 자동 발송이 만든 것이다. */
  sender: z.string(),
  body: z.string(),
  sentAt: z.string(),
});
export type InboxMessage = z.infer<typeof messageSchema>;

export const threadSchema = z.object({
  thread: threadSummarySchema,
  messages: z.array(messageSchema).default([]),
  /** 거짓이면 입력창 대신 미지원 표시를 그린다. iCal 스레드가 여기 걸린다. */
  messagingSupported: z.boolean(),
});
export type InboxThread = z.infer<typeof threadSchema>;

export const messageTemplateSchema = z.object({
  id: z.number(),
  code: z.string(),
  name: z.string(),
  body: z.string(),
});
export type MessageTemplate = z.infer<typeof messageTemplateSchema>;

// --- 운영 태스크 (P4 15주차) --------------------------------------------------

/** 칸반의 네 칸. 서버의 `TaskStatus` 와 같은 값이다. */
export const TASK_STATUSES = ['TODO', 'IN_PROGRESS', 'DONE', 'BLOCKED'] as const;
export type TaskStatus = (typeof TASK_STATUSES)[number];

/**
 * 청소 태스크 한 건. 계획서 8.6.
 *
 * **`overdue` 를 서버가 판정한다.** 브라우저 시계로 계산하면 시각이 어긋난 기기에서
 * 멀쩡한 태스크가 빨갛게 뜬다.
 *
 * `dueTo` 가 없는 것은 **기한이 열려 있다**는 뜻이다. 다음 예약이 아직 없다.
 * 값이 없는 필드는 키가 아예 빠지므로(`non_null` 직렬화) `nullish` 로 받는다.
 */
export const opsTaskSchema = z.object({
  id: z.number(),
  unitId: z.number().nullish(),
  unitName: z.string().nullish(),
  reservationId: z.number().nullish(),
  taskType: z.string(),
  status: z.enum(TASK_STATUSES),
  assigneeName: z.string().nullish(),
  dueFrom: z.string().nullish(),
  dueTo: z.string().nullish(),
  completedAt: z.string().nullish(),
  overdue: z.boolean(),
});
export type OpsTask = z.infer<typeof opsTaskSchema>;

// --- 리포트 (P4 16주차) --------------------------------------------------------

/** 채널 하나의 몫. 서버가 매출 내림차순으로 보낸다. */
export const channelShareSchema = z.object({
  channelCode: z.string(),
  reservations: z.number(),
  revenue: z.number(),
  /** 0.0 ~ 1.0. 전체 매출이 0이면 0이다. */
  revenueShare: z.number(),
});
export type ChannelShare = z.infer<typeof channelShareSchema>;

/**
 * 리포트 지표 여섯. 계획서 8.8.
 *
 * **비율은 0.0 ~ 1.0 으로 온다.** 백분율로 바꾸는 것은 화면의 몫이다.
 *
 * **분모가 0이면 서버가 0을 보낸다.** 예약이 없는 기간을 보는 것은 정상이고 —
 * 새 숙소를 등록한 직후가 그렇다 — 그때 화면이 터지면 안 된다. "값이 없다"와
 * "0이다"를 구분해 보여 주는 것이 화면의 몫이라 `soldNights` 를 함께 받는다.
 */
export const reportMetricsSchema = z.object({
  soldNights: z.number(),
  availableNights: z.number(),
  roomRevenue: z.number(),
  occupancyRate: z.number(),
  adr: z.number(),
  revPar: z.number(),
  leadTimeDays: z.number(),
  cancellationRate: z.number(),
  channelMix: z.array(channelShareSchema).default([]),
});
export type ReportMetrics = z.infer<typeof reportMetricsSchema>;
