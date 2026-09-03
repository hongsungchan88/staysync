import { apiRequest } from './client';
import { bulkEditResultSchema, type BulkEditResult } from './schemas';

/** 요금 변경 방식. 백엔드가 `priceMode` 로 갈린다. */
export type PriceMode = 'FIXED' | 'PERCENT';

export interface BulkEditInput {
  unitIds: number[];
  from: string;
  to: string;
  /** 빈 배열이면 전체 요일. 백엔드도 같은 규칙이다. */
  weekdays: string[];
  priceMode: PriceMode | null;
  price: number | null;
  priceRate: number | null;
  minStay: number | null;
  closedToArrival: boolean | null;
  stopSell: boolean | null;
  dryRun: boolean;
}

/**
 * 요금·제약 일괄 편집.
 *
 * **미리보기와 적용이 같은 엔드포인트다.** `dryRun` 하나로 갈린다. 별도 경로로 두면
 * 언젠가 갈라지고, 갈라진 순간의 증상은 "미리보기에 20건이라 했는데 22건이 바뀌었다"라
 * 사용자가 적용한 뒤에야 안다.
 */
export async function bulkEdit(
  propertyId: number,
  input: BulkEditInput,
): Promise<BulkEditResult> {
  const body = await apiRequest<unknown>(
    `/api/properties/${propertyId}/calendar/bulk-edit`,
    { method: 'POST', body: input },
  );
  return bulkEditResultSchema.parse(body);
}
