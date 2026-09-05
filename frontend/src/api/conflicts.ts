import { z } from 'zod';
import { apiRequest } from './client';
import { conflictSchema, type Conflict } from './schemas';

/**
 * 중복예약 충돌. 계획서 7.4.
 *
 * **채널 로그 아래가 아니라 따로 있다.** 충돌은 채널 하나의 문제가 아니라 그 날짜
 * 그 방의 문제라, 채널을 하나씩 열어 봐야 보이면 운영자가 찾지 못한다.
 */
export async function fetchConflicts(): Promise<Conflict[]> {
  const body = await apiRequest<unknown>('/api/conflicts');
  return z.array(conflictSchema).parse(body);
}

/** 계획서 7.4 의 해소 방법 넷. 서버의 `OverbookingConflict` 상수와 같은 값이다. */
export type Resolution = 'UPGRADED' | 'RELOCATED' | 'CANCELLED' | 'ABSORBED';

export interface ResolveInput {
  resolution: Resolution;
  /** 옮기거나 취소할 예약. 한 자리에 예약이 둘 이상이라 운영자가 골라야 한다. */
  reservationId?: number;
  /** `UPGRADED` 일 때만 쓴다. 예약을 옮길 판매 단위. */
  targetUnitId?: number;
  memo?: string;
}

export async function resolveConflict(
  conflictId: number,
  input: ResolveInput,
): Promise<Conflict> {
  const body = await apiRequest<unknown>(`/api/conflicts/${conflictId}/resolve`, {
    method: 'POST',
    body: input,
  });
  return conflictSchema.parse(body);
}
