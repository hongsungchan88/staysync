import { z } from 'zod';
import { apiRequest } from './client';
import { opsTaskSchema, type OpsTask, type TaskStatus } from './schemas';

/**
 * 청소 태스크 칸반. 계획서 8.6.
 *
 * **칸별로 나누어 받지 않는다.** 한 배열을 받아 화면이 상태로 묶는다 — 칸 사이
 * 이동에서 낙관적 업데이트를 하려면 어차피 한 배열을 쥐고 옮겨야 한다.
 */
export interface BoardFilter {
  /** 둘 다 `YYYY-MM-DD`. 비우면 전 기간이다. */
  from?: string;
  to?: string;
  assignee?: string;
}

export async function fetchOpsTasks(filter: BoardFilter = {}): Promise<OpsTask[]> {
  const query = new URLSearchParams();
  if (filter.from) query.set('from', filter.from);
  if (filter.to) query.set('to', filter.to);
  if (filter.assignee) query.set('assignee', filter.assignee);

  const suffix = query.toString();
  const body = await apiRequest<unknown>(`/api/ops/tasks${suffix ? `?${suffix}` : ''}`);
  return z.array(opsTaskSchema).parse(body);
}

export interface TaskPatch {
  status?: TaskStatus;
  /** 빈 문자열은 담당자를 지운다. `undefined` 는 건드리지 않는다. */
  assigneeName?: string;
}

export async function patchOpsTask(taskId: number, patch: TaskPatch): Promise<OpsTask> {
  const body = await apiRequest<unknown>(`/api/ops/tasks/${taskId}`, {
    method: 'PATCH',
    body: patch,
  });
  return opsTaskSchema.parse(body);
}
