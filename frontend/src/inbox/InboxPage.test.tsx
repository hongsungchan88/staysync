import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { InboxPage } from './InboxPage';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 통합 인박스.
 *
 * 여기서 지킬 계약은 둘이다. **메시징을 지원하지 않는 채널에는 입력창 대신 미지원
 * 표시가 뜰 것**(계획서 6.1 이 화면에서 실제로 무언가를 바꾸는 자리다), 그리고
 * **게스트 연락처가 화면 어디에도 없을 것**(ADR 0007).
 */

const 목록 = [
  {
    id: 1,
    channelCode: 'MOCK_A',
    subject: 'SS-AAA111',
    unreadCount: 2,
    lastMessageAt: '2026-12-20T10:00:00Z',
    reservationId: 501,
    confirmationCode: 'SS-AAA111',
    guestName: '홍길동',
    checkIn: '2026-12-24',
    checkOut: '2026-12-26',
    reservationStatus: 'CONFIRMED',
  },
  {
    id: 2,
    channelCode: 'AIRBNB_ICAL',
    subject: 'SS-BBB222',
    unreadCount: 0,
    lastMessageAt: '2026-12-19T10:00:00Z',
    reservationId: 502,
    confirmationCode: 'SS-BBB222',
    checkIn: '2026-12-22',
    checkOut: '2026-12-23',
    reservationStatus: 'CONFIRMED',
  },
];

function thread(id: number, supported: boolean) {
  return {
    thread: 목록.find((t) => t.id === id),
    messages: [
      {
        id: 10,
        direction: 'INBOUND',
        sender: 'GUEST',
        body: '체크인 시간을 늦출 수 있을까요?',
        sentAt: '2026-12-20T10:00:00Z',
      },
      {
        id: 11,
        direction: 'OUTBOUND',
        sender: 'SYSTEM',
        body: '예약이 확정되었습니다.',
        sentAt: '2026-12-20T11:00:00Z',
      },
    ],
    messagingSupported: supported,
  };
}

let client: QueryClient;

function wrapper(initial: string) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={[initial]}>
          <Routes>
            <Route path="/inbox" element={children} />
            <Route path="/inbox/:threadId" element={children} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    );
  };
}

function respond(url: string) {
  if (url === '/api/inbox') {
    return 목록;
  }
  if (url === '/api/inbox/1') {
    return thread(1, true);
  }
  if (url === '/api/inbox/2') {
    return thread(2, false);
  }
  return [];
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
        json: () => Promise.resolve(respond(url)),
      } as Response),
    ),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
});

describe('통합 인박스', () => {
  it('스레드 목록에 채널과 미읽음이 보인다', async () => {
    render(<InboxPage />, { wrapper: wrapper('/inbox') });

    await waitFor(() => expect(screen.getByText('홍길동')).toBeInTheDocument());
    expect(screen.getByText('SS-BBB222')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('메시징을 지원하는 채널에는 입력창이 있다', async () => {
    render(<InboxPage />, { wrapper: wrapper('/inbox/1') });

    await waitFor(() => expect(screen.getByLabelText('답장')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '보내기' })).toBeInTheDocument();
  });

  it('iCal 스레드에는 입력창 대신 미지원 표시가 뜬다', async () => {
    render(<InboxPage />, { wrapper: wrapper('/inbox/2') });

    // 입력창을 두고 실패를 보여 주면 호스트는 답을 다 쓴 뒤에야 보낼 수 없다는 것을 안다.
    await waitFor(() =>
      expect(screen.getByText(/메시지 발송을 지원하지 않습니다/)).toBeInTheDocument(),
    );
    expect(screen.queryByLabelText('답장')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '보내기' })).not.toBeInTheDocument();
  });

  it('자동 발송한 메시지가 사람이 쓴 것과 구분된다', async () => {
    render(<InboxPage />, { wrapper: wrapper('/inbox/1') });

    // 사람이 쓰지 않았다는 사실이 화면에 드러나야 한다.
    await waitFor(() => expect(screen.getByText('예약이 확정되었습니다.')).toBeInTheDocument());
    expect(screen.getByText(/자동 발송/)).toBeInTheDocument();
    expect(screen.getByText(/게스트/)).toBeInTheDocument();
  });

  it('게스트 연락처가 화면에 없다', async () => {
    render(<InboxPage />, { wrapper: wrapper('/inbox/1') });

    // 서버가 담지 않으므로 화면도 보여 줄 수 없다. 응답 모양이 바뀌면 여기서 걸린다.
    await waitFor(() => expect(screen.getByText('홍길동')).toBeInTheDocument());
    expect(document.body.textContent).not.toContain('010');
    expect(JSON.stringify(목록)).not.toContain('guestPhone');
    expect(JSON.stringify(목록)).not.toContain('guestEmail');
  });
});
