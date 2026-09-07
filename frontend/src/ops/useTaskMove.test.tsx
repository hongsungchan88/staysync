import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { useTaskMove } from './useTaskMove';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';
import type { OpsTask } from '@/api/schemas';

/**
 * 완료 조건 8·9. 칸 사이 이동과 **거절되면 원위치**.
 *
 * 8주차 예약 막대와 같은 성질이다. 실패를 삼키면 화면에는 완료로 보이는데 판매 단위는
 * 여전히 더러운 상태가 남고, **그 화면은 정상으로 보인다.** 담당자가 헛걸음한 뒤에야
 * 드러난다.
 */

const KEY = ['ops-tasks', '', '', ''] as const;

function taskAt(status: OpsTask['status']): OpsTask[] {
  return [
    {
      id: 42,
      unitId: 3,
      unitName: '객실 01',
      reservationId: 9,
      taskType: 'CLEANING',
      status,
      assigneeName: null,
      dueFrom: '2027-04-03T11:00:00+09:00',
      dueTo: null,
      completedAt: null,
      overdue: false,
    },
  ];
}

function statusOf(): string {
  return client.getQueryData<OpsTask[]>(KEY)![0]!.status;
}

let client: QueryClient;
let fetchMock: ReturnType<typeof vi.fn>;

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function jsonResponse(status: number, body: unknown) {
  return { ok: status < 400, status, json: async () => body };
}

beforeEach(() => {
  resetRefreshState();
  tokenStore.set('테스트-토큰');
  client = new QueryClient({
    // 실패를 되돌리는지 보는 테스트다. 자동 재시도가 있으면 그 지점이 흐려진다.
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  client.setQueryData(KEY, taskAt('TODO'));
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
  resetRefreshState();
});

describe('청소 태스크 칸 옮기기', () => {
  it('옮기면 상태가 바뀌고 서버에 반영된다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { ...taskAt('DONE')[0] }));

    const { result } = renderHook(() => useTaskMove(KEY, () => {}), { wrapper });
    result.current.move({ taskId: 42, next: 'DONE' });

    await waitFor(() => expect(statusOf()).toBe('DONE'));

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toContain('/api/ops/tasks/42');
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(init.body)).toEqual({ status: 'DONE' });
  });

  it('서버가 거절하면 원래 칸으로 돌아오고 이유가 나온다', async () => {
    // 응답을 손으로 풀어 준다. 그러지 않으면 되돌리기가 너무 빨라, 화면이 먼저 옮겨졌다는
    // 사실을 관찰할 수 없다. 사용자가 보는 순서까지 확인하는 것이 이 테스트의 요지다.
    let release = () => {};
    const pending = new Promise<void>((resolve) => {
      release = resolve;
    });
    fetchMock.mockImplementation(async () => {
      await pending;
      return jsonResponse(404, {
        code: 'OPS_TASK_NOT_FOUND',
        message: '태스크를 찾을 수 없습니다. id=42',
        details: [],
      });
    });

    const rejections: string[] = [];
    const { result } = renderHook(() => useTaskMove(KEY, (r) => rejections.push(r)), { wrapper });

    result.current.move({ taskId: 42, next: 'DONE' });

    // 먼저 옮겨 보인다. 이게 낙관적 업데이트다.
    await waitFor(() => expect(statusOf()).toBe('DONE'));

    release();

    // 그리고 되돌아와야 한다. 여기가 이 테스트의 전부다.
    await waitFor(() => expect(statusOf()).toBe('TODO'));
    // 왜 안 됐는지도 보여야 한다. 조용히 되돌아가면 사용자는 드래그가 안 먹혔다고 본다.
    await waitFor(() => expect(rejections).toHaveLength(1));
    expect(rejections[0]).toContain('찾을 수 없습니다');
  });

  it('제자리에 놓으면 왕복하지 않는다', () => {
    const { result } = renderHook(() => useTaskMove(KEY, () => {}), { wrapper });

    result.current.move({ taskId: 42, next: 'TODO' });

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
