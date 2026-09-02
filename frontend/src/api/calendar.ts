import { apiRequest } from './client';
import {
  calendarGridSchema,
  propertySummarySchema,
  type CalendarGrid,
  type PropertySummary,
} from './schemas';
import { z } from 'zod';

export async function fetchProperties(): Promise<PropertySummary[]> {
  const body = await apiRequest<unknown>('/api/properties');
  return z.array(propertySummarySchema).parse(body);
}

/**
 * 캘린더 그리드.
 *
 * `from` 과 `to` 는 양끝을 포함한다. 서버가 365일 상한을 강제하므로 화면이 그보다 넓은
 * 범위를 요청하면 400 이 온다.
 */
export async function fetchCalendar(
  propertyId: number,
  from: string,
  to: string,
): Promise<CalendarGrid> {
  const query = new URLSearchParams({ from, to });
  const body = await apiRequest<unknown>(`/api/properties/${propertyId}/calendar?${query}`);
  return calendarGridSchema.parse(body);
}
