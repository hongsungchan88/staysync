import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { CalendarPage } from './CalendarPage';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';
import { addDays, toIso } from '@/lib/dates';

/** 작업지시-29 — 상단 메뉴(지금 화면·계정 자리)와 좁은 화면의 아래 판. */

const TODAY = toIso(new Date());

function grid() {
  return {
    from: TODAY,
    to: addDays(TODAY, 29),
    units: [
      {
        id: 1,
        name: '본채',
        totalUnits: 1,
        days: Array.from({ length: 30 }, (_, d) => ({
          date: addDays(TODAY, d),
          avail: 1,
          price: 100000,
          minStay: 1,
          stopSell: false,
          conflict: false,
        })),
      },
    ],
    reservations: [
      {
        id: 77, unitId: 1, checkIn: TODAY, checkOut: addDays(TODAY, 2),
        guestName: '김손님', channel: 'DIRECT', status: 'CONFIRMED', amount: 200000,
      },
    ],
  };
}

function json(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) });
}

let client: QueryClient;

function renderPage() {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/']}>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<CalendarPage />, { wrapper });
}

/** `matchMedia` 를 흉내 낸다 — 없으면 넓은 화면이다(`useNarrow`). */
function emulateWidth(narrow: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: narrow,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }));
}

beforeEach(() => {
  resetRefreshState();
  tokenStore.set('테스트-토큰');
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(1200);
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(800);
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url === '/api/properties') {
        return json([{ id: 1, name: '홍대 지니하우스', timezone: 'Asia/Seoul', currency: 'KRW', checkInTime: '15:00:00', checkOutTime: '11:00:00', status: 'ACTIVE' }]);
      }
      if (url === '/api/channels') return json([]);
      if (url.startsWith('/api/properties/1/calendar')) return json(grid());
      // 실시간 스트림은 열린 채로 둔다.
      return new Promise(() => {});
    }),
  );
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  tokenStore.clear();
  resetRefreshState();
});

describe('상단 메뉴', () => {
  it('지금 화면 메뉴에만 aria-current 가 있고, 계정은 주 메뉴 밖에 따로 있다', async () => {
    renderPage();

    const nav = await screen.findByRole('navigation', { name: '주 메뉴' });
    expect(within(nav).getByRole('link', { name: '캘린더' })).toHaveAttribute('aria-current', 'page');
    for (const name of ['숙소', '채널 연결', '충돌', '인박스', '청소', '리포트']) {
      expect(within(nav).getByRole('link', { name })).not.toHaveAttribute('aria-current');
    }
    expect(within(nav).queryByText('로그아웃')).not.toBeInTheDocument();

    const account = screen.getByRole('group', { name: '계정' });
    expect(within(account).getByRole('link', { name: '비밀번호 변경' })).toHaveAttribute('href', '/account/password');
    expect(within(account).getByRole('button', { name: '로그아웃' })).toBeInTheDocument();
  });
});

describe('좁은 화면의 아래 판', () => {
  it('막대를 누르면 판이 열리고 Esc 로 접힌다 — 다시 "예약 추가"만 남는다', async () => {
    emulateWidth(true);
    renderPage();

    fireEvent.click(await screen.findByTestId('bar-77'));
    const dock = screen.getByTestId('panel-dock');
    expect(within(dock).getByTestId('reservation-panel')).toBeInTheDocument();

    fireEvent.keyDown(window, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('reservation-panel')).not.toBeInTheDocument());
    expect(within(dock).getByTestId('add-reservation-button')).toBeInTheDocument();

    // "예약 추가"를 열고 Esc 로 닫는다.
    fireEvent.click(within(dock).getByTestId('add-reservation-button'));
    expect(screen.getByTestId('new-reservation-panel')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('new-reservation-panel')).not.toBeInTheDocument());
  });

  it('넓은 화면에서는 Esc 가 예약 패널을 닫지 않는다 — 지금 동작 그대로', async () => {
    emulateWidth(false);
    renderPage();

    fireEvent.click(await screen.findByTestId('bar-77'));
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.getByTestId('reservation-panel')).toBeInTheDocument();
  });
});
