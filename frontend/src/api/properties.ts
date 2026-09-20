import { apiRequest } from './client';
import { propertySummarySchema, type PropertySummary } from './schemas';
import { z } from 'zod';

/**
 * 숙소·판매 단위 등록·편집(작업지시-19 B·C). 서버는 P1 3주차의 API 그대로다.
 *
 * **수량만 다른 경로다.** `PATCH /api/units/{id}` 는 이름만 받고, 수량은
 * `PATCH /api/units/{id}/capacity`(booking) 로 간다 — 원장 행이 함께 따라가야 하는 쓰기라
 * 재고 엔진이 든다. 이미 팔린 날 아래로 줄이면 409 `CAPACITY_BELOW_BOOKINGS` 이고
 * `details` 에 막는 날짜들이 온다.
 */

/** 백엔드 `PropertyDtos.UnitResponse` 와 짝. */
export const unitSchema = z.object({
  id: z.number(),
  propertyId: z.number(),
  name: z.string(),
  unitKind: z.enum(['ENTIRE_PLACE', 'PRIVATE_ROOM', 'SHARED_ROOM']),
  totalUnits: z.number(),
  basePrice: z.number(),
  housekeeping: z.string(),
});
export type Unit = z.infer<typeof unitSchema>;
export type UnitKind = Unit['unitKind'];

export const UNIT_KIND_LABEL: Record<UnitKind, string> = {
  ENTIRE_PLACE: '독채',
  PRIVATE_ROOM: '개인실',
  SHARED_ROOM: '다인실',
};

export async function createProperty(input: {
  name: string;
  address?: string;
}): Promise<PropertySummary> {
  const body = await apiRequest<unknown>('/api/properties', { method: 'POST', body: input });
  return propertySummarySchema.parse(body);
}

export async function updateProperty(
  propertyId: number,
  input: { name?: string; address?: string; checkInTime?: string; checkOutTime?: string },
): Promise<PropertySummary> {
  const body = await apiRequest<unknown>(`/api/properties/${propertyId}`, {
    method: 'PATCH',
    body: input,
  });
  return propertySummarySchema.parse(body);
}

export async function fetchUnits(propertyId: number): Promise<Unit[]> {
  const body = await apiRequest<unknown>(`/api/properties/${propertyId}/units`);
  return z.array(unitSchema).parse(body);
}

export async function createUnit(
  propertyId: number,
  input: { name: string; unitKind: UnitKind; totalUnits: number; basePrice: number },
): Promise<Unit> {
  const body = await apiRequest<unknown>(`/api/properties/${propertyId}/units`, {
    method: 'POST',
    body: input,
  });
  return unitSchema.parse(body);
}

export async function renameUnit(unitId: number, name: string): Promise<Unit> {
  const body = await apiRequest<unknown>(`/api/units/${unitId}`, {
    method: 'PATCH',
    body: { name },
  });
  return unitSchema.parse(body);
}

/** `/capacity` 응답은 종류·청소 상태가 없는 작은 모양이다. 화면은 저장 뒤 목록을 다시 읽는다. */
const unitCapacitySchema = unitSchema.pick({ id: true, propertyId: true, name: true, totalUnits: true, basePrice: true });

export async function changeUnitCapacity(
  unitId: number,
  totalUnits: number,
): Promise<z.infer<typeof unitCapacitySchema>> {
  const body = await apiRequest<unknown>(`/api/units/${unitId}/capacity`, {
    method: 'PATCH',
    body: { totalUnits },
  });
  return unitCapacitySchema.parse(body);
}
