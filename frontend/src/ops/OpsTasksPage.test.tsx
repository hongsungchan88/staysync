import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { OpsTasksPage } from './OpsTasksPage';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 완료 조건 10·11·13. 칸반 화면.
 *
 * **10번이 이 파일에서 가장 중요하다.** 드래그로만 되는 조작은 마우스가 없으면 못 쓰는
 * 기능이 된다 — 8주차에 정한 것이 그대로 적용된다. dnd-kit 의 키보드 센서는 jsdom 에서
 * 좌표 계산이 돌지 않아 검증할 수 없으므로, **같은 조작을 목록으로도 열어 두고 그쪽을
 * 확인한다.** 드래그 경로는 브라우저 확인(완료 조건 15)이 맡는다.
 */

const 태스크 = [
  {
    id: 1,
    unitId: 11,
    unitName: '객실 01',
    reservationId: 101,
    taskType: 'CLEANING',
    status: 'TODO',
    assigneeName: '김청소',
    dueFrom: '2027-04-03T11:00:00+09:00',
    dueTo: '2027-04-04T15:00:00+09:00',
    completedAt: null,
    overdue: true,
  },
  {
    id: 2,
    unitId: 12,
    unitName: '객실 02',
    reservationId: 102,
    taskType: 'CLEANING',
    status: 'IN_PROGRESS',
    dueFrom: '2027-04-05T11:00:00+09:00',
    completedAt: null,
    overdue: false,
  },
];

let client: QueryClient;
let fetchMock: ReturnType<typeof vi.fn>;
/** 마지막 목록 요청의 주소. 필터가 실제로 실려 나가는지 본다. */
let lastListUrl = '';

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  tokenStore.set('test-token');
  resetRefreshState();
  lastListUrl = '';
  fetchMock = vi.fn((url: string, init?: { method?: string; body?: string }) => {
    if (init?.method === 'PATCH') {
      const patch = JSON.parse(init.body ?? '{}');
      return Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve({ ...태스크[0], ...patch }),
      } as Response);
    }
    lastListUrl = url;
    return Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve(태스크),
    } as Response);
  });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
  resetRefreshState();
});

describe('청소 태스크 칸반', () => {
  it('태스크가 상태별 칸에 놓인다', async () => {
    render(<OpsTasksPage />, { wrapper });

    await waitFor(() => expect(screen.getByTestId('task-1')).toBeInTheDocument());

    expect(within(screen.getByTestId('column-TODO')).getByText('객실 01')).toBeInTheDocument();
    expect(
      within(screen.getByTestId('column-IN_PROGRESS')).getByText('객실 02'),
    ).toBeInTheDocument();
  });

  // --- 완료 조건 10 --------------------------------------------------------

  it('마우스 없이 키보드만으로 칸을 옮길 수 있다', async () => {
    const user = userEvent.setup();
    render(<OpsTasksPage />, { wrapper });
    await waitFor(() => expect(screen.getByTestId('task-1')).toBeInTheDocument());

    // 탭으로 닿고 키보드로 고른다. 포인터 이벤트를 쓰지 않는다.
    const select = screen.getByLabelText('객실 01 칸 옮기기');
    await user.selectOptions(select, 'DONE');

    await waitFor(() => {
      const patch = fetchMock.mock.calls.find((call) => call[1]?.method === 'PATCH');
      expect(patch).toBeDefined();
      expect(JSON.parse(patch![1].body)).toEqual({ status: 'DONE' });
    });
  });

  // --- 완료 조건 11 --------------------------------------------------------

  it('날짜와 담당자 필터가 요청에 실린다', async () => {
    const user = userEvent.setup();
    render(<OpsTasksPage />, { wrapper });
    await waitFor(() => expect(screen.getByTestId('task-1')).toBeInTheDocument());

    await user.type(screen.getByLabelText('담당자'), '김청소');

    await waitFor(() => expect(lastListUrl).toContain('assignee=%EA%B9%80%EC%B2%AD%EC%86%8C'));

    await user.clear(screen.getByLabelText('담당자'));
    await user.type(screen.getByLabelText('시작 날짜'), '2027-04-01');

    await waitFor(() => expect(lastListUrl).toContain('from=2027-04-01'));
  });

  // --- 완료 조건 13 --------------------------------------------------------

  it('기한이 지난 태스크가 구분돼 보인다', async () => {
    render(<OpsTasksPage />, { wrapper });

    await waitFor(() => expect(screen.getByTestId('task-1')).toBeInTheDocument());

    // 판정은 서버가 한다. 브라우저 시계로 계산하면 시각이 어긋난 기기에서
    // 멀쩡한 태스크가 빨갛게 뜬다.
    expect(screen.getByTestId('overdue-1')).toHaveTextContent('기한 지남');
    expect(screen.queryByTestId('overdue-2')).not.toBeInTheDocument();
  });

  it('기한이 열린 태스크는 빈칸이 아니라 열림으로 보인다', async () => {
    render(<OpsTasksPage />, { wrapper });

    await waitFor(() => expect(screen.getByTestId('task-2')).toBeInTheDocument());

    // 빈칸으로 두면 값이 빠진 것으로 읽힌다. 다음 예약이 아직 없다는 뜻이다.
    expect(within(screen.getByTestId('task-2')).getByText(/열림/)).toBeInTheDocument();
  });
});
