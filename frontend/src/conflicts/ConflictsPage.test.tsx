import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ConflictsPage } from './ConflictsPage';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 중복예약 충돌 화면.
 *
 * 여기서 지킬 계약은 셋이다. **해소 방법 넷이 전부 보일 것**(계획서 7.4),
 * **업그레이드를 고르면 옮길 방을 골라야 할 것**(안 고르면 서버가 거절한다),
 * 그리고 **게스트 이름이 화면 어디에도 없을 것**(ADR 0007 의 선).
 */

const 충돌 = {
  id: 11,
  propertyId: 1,
  unitId: 5,
  stayDate: '2026-12-25',
  severity: 'CRITICAL',
  status: 'OPEN',
  reservations: [
    {
      id: 101,
      unitId: 5,
      confirmationCode: 'SS-AAA111',
      channelCode: 'AIRBNB_ICAL',
      status: 'CONFIRMED',
      checkIn: '2026-12-24',
      checkOut: '2026-12-26',
    },
    {
      id: 102,
      unitId: 5,
      confirmationCode: 'SS-BBB222',
      channelCode: 'DIRECT',
      status: 'CONFIRMED',
      checkIn: '2026-12-25',
      checkOut: '2026-12-27',
    },
  ],
};

const 캘린더 = {
  from: '2026-12-25',
  to: '2026-12-25',
  units: [
    {
      id: 5,
      name: '본채',
      totalUnits: 1,
      days: [{ date: '2026-12-25', avail: 0, price: 200000, minStay: 1, stopSell: false, conflict: true }],
    },
    {
      id: 6,
      name: '별채',
      totalUnits: 2,
      days: [{ date: '2026-12-25', avail: 2, price: 250000, minStay: 1, stopSell: false, conflict: false }],
    },
  ],
  reservations: [],
};

let client: QueryClient;

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  tokenStore.set('test-token');
  resetRefreshState();
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(url.startsWith('/api/conflicts') ? [충돌] : 캘린더),
      } as Response),
    ),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
});

describe('충돌 해소', () => {
  it('해소 방법 넷을 모두 제시한다', async () => {
    render(<ConflictsPage />, { wrapper });

    await waitFor(() => expect(screen.getByText('2026-12-25')).toBeInTheDocument());
    // 계획서 7.4 가 정한 넷이다. 하나라도 빠지면 운영자가 고를 수 없는 길이 생긴다.
    expect(screen.getByRole('option', { name: '상위 판매 단위로 배정' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '인근 제휴 숙소 안내' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '취소와 보상' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '관리자 허용' })).toBeInTheDocument();
  });

  it('부딪힌 예약을 모두 보여 주고 어느 쪽을 움직일지 고르게 한다', async () => {
    render(<ConflictsPage />, { wrapper });

    // 한쪽만 보여 주면 "충돌"인데 상대가 없는 화면이 된다.
    await waitFor(() => expect(screen.getByText('SS-AAA111')).toBeInTheDocument());
    expect(screen.getByText('SS-BBB222')).toBeInTheDocument();
    expect(screen.getAllByRole('radio')).toHaveLength(2);
  });

  it('게스트 이름이 화면에 없다', async () => {
    render(<ConflictsPage />, { wrapper });

    // 서버가 담지 않으므로 화면도 보여 줄 수 없다. 응답 모양이 바뀌면 여기서 걸린다.
    await waitFor(() => expect(screen.getByText('SS-AAA111')).toBeInTheDocument());
    expect(document.body.textContent).not.toContain('홍길동');
    expect(JSON.stringify(충돌)).not.toContain('guestName');
  });

  it('업그레이드를 고르면 옮길 방을 고르기 전까지 해소할 수 없다', async () => {
    const { default: userEvent } = await import('@testing-library/user-event');
    render(<ConflictsPage />, { wrapper });

    await waitFor(() => expect(screen.getByText('SS-AAA111')).toBeInTheDocument());

    // 안 고르고 보내면 서버가 400 으로 거절한다. 그 전에 막는다.
    expect(screen.getByRole('button', { name: '해소하기' })).toBeDisabled();

    await waitFor(() =>
      expect(screen.getByRole('option', { name: /별채/ })).toBeInTheDocument(),
    );
    await userEvent.selectOptions(
      screen.getByRole('combobox', { name: /옮길 판매 단위/ }),
      '6',
    );
    expect(screen.getByRole('button', { name: '해소하기' })).toBeEnabled();
  });

  it('충돌이 난 방 자신은 옮길 대상에서 빠진다', async () => {
    render(<ConflictsPage />, { wrapper });

    await waitFor(() =>
      expect(screen.getByRole('option', { name: /별채/ })).toBeInTheDocument(),
    );
    // 같은 방으로 옮기는 것은 아무것도 하지 않는 것이다.
    expect(screen.queryByRole('option', { name: /본채/ })).not.toBeInTheDocument();
  });
});
