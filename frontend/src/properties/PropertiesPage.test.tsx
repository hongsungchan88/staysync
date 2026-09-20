import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { PropertiesPage } from './PropertiesPage';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 작업지시-19 완료 조건 10 의 등록 화면. 픽스처는 서버가 실제로 보내는 모양 그대로다 —
 * `PropertySummary` 는 `address` 가 없으면 키가 빠지고, 수량 줄이기 거절은
 * `ApiError` 409 에 `details` 가 날짜 목록이다.
 */

const 숙소 = {
  id: 7,
  name: '홍대 지니하우스',
  timezone: 'Asia/Seoul',
  currency: 'KRW',
  checkInTime: '15:00:00',
  checkOutTime: '11:00:00',
  status: 'ACTIVE',
};

const 판매단위 = {
  id: 3,
  propertyId: 7,
  name: '지니하우스',
  unitKind: 'ENTIRE_PLACE',
  totalUnits: 1,
  basePrice: 170000,
  housekeeping: 'CLEAN',
};

let client: QueryClient;
let fetchMock: ReturnType<typeof vi.fn>;

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

function json(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  tokenStore.set('test-token');
  resetRefreshState();
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
  client.clear();
});

describe('숙소·판매 단위 화면', () => {
  it('숙소가 없으면 안내를 보여 주고, 추가하면 목록에 생긴다', async () => {
    let created = false;
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/api/properties' && init?.method === 'POST') {
        created = true;
        expect(JSON.parse(init.body as string)).toEqual({ name: '새 숙소' });
        return Promise.resolve(json({ ...숙소, id: 8, name: '새 숙소' }, 201));
      }
      if (url === '/api/properties') {
        return Promise.resolve(json(created ? [{ ...숙소, id: 8, name: '새 숙소' }] : []));
      }
      if (url === '/api/properties/8/units') {
        return Promise.resolve(json([]));
      }
      return Promise.reject(new Error('예상 밖 요청 ' + url));
    });
    render(<PropertiesPage />, { wrapper });

    expect(await screen.findByTestId('properties-empty')).toHaveTextContent('숙소를 먼저 등록');

    await userEvent.type(screen.getByLabelText('숙소 이름'), '새 숙소');
    await userEvent.click(screen.getByRole('button', { name: '숙소 추가' }));

    expect(await screen.findByRole('region', { name: '숙소 새 숙소' })).toBeInTheDocument();
    expect(screen.queryByTestId('properties-empty')).not.toBeInTheDocument();
  });

  it('수량을 팔린 날 아래로 줄이면 서버가 준 막는 날짜를 그대로 보여 준다', async () => {
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/api/units/3/capacity' && init?.method === 'PATCH') {
        expect(JSON.parse(init.body as string)).toEqual({ totalUnits: 1 });
        return Promise.resolve(
          json(
            {
              code: 'CAPACITY_BELOW_BOOKINGS',
              message: '이미 팔린 날이 있어 수량을 1 으로 줄일 수 없습니다. 막는 날짜 2 일 — 첫 날 2027-03-01',
              details: ['2027-03-01', '2027-03-02'],
            },
            409,
          ),
        );
      }
      if (url === '/api/properties') return Promise.resolve(json([숙소]));
      if (url === '/api/properties/7/units') {
        return Promise.resolve(json([{ ...판매단위, totalUnits: 2 }]));
      }
      return Promise.reject(new Error('예상 밖 요청 ' + url));
    });
    render(<PropertiesPage />, { wrapper });

    const 행 = await screen.findByRole('listitem', { name: '판매 단위 지니하우스' });
    const 수량 = within(행).getByLabelText('지니하우스 수량');
    await userEvent.clear(수량);
    await userEvent.type(수량, '1');
    await userEvent.click(within(행).getByRole('button', { name: '수량 저장' }));

    // 날짜를 알아야 어느 예약을 옮길지 고른다. 메시지만 보여 주면 막힌 이유를 모른다.
    const alert = await screen.findByTestId('capacity-blocked');
    expect(alert).toHaveTextContent('2027-03-01, 2027-03-02');
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/units/3/capacity', expect.anything()));
  });
});
