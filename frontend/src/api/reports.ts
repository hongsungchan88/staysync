import { apiRequest } from './client';
import { reportMetricsSchema, type ReportMetrics } from './schemas';

/**
 * 운영 리포트. 계획서 8.8.
 *
 * **한 번에 다 받는다.** 지표마다 따로 부르면 한 화면이 여섯 번 왕복하고, 그 여섯이
 * 서로 다른 순간의 값을 보여 줄 수 있다. 서버도 응답 하나다.
 */
export interface ReportFilter {
  /** 비우면 조직의 숙소 전부다. */
  propertyId?: number;
  /** 둘 다 `YYYY-MM-DD` 이고 양끝을 포함한다. */
  from: string;
  to: string;
}

export async function fetchReport(filter: ReportFilter): Promise<ReportMetrics> {
  const query = new URLSearchParams({ from: filter.from, to: filter.to });
  if (filter.propertyId !== undefined) {
    query.set('propertyId', String(filter.propertyId));
  }
  const body = await apiRequest<unknown>(`/api/reports?${query}`);
  return reportMetricsSchema.parse(body);
}
