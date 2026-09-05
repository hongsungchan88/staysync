import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ChannelsPage } from './ChannelsPage';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 채널 목록 화면.
 *
 * 여기서 지킬 계약은 둘이다. **iCal 연결에 "요금 전파 미지원"이 보일 것**(설계의 요점이
 * 화면에 드러나는 자리다), 그리고 **자격 증명 평문이 화면 어디에도 없을 것**.
 */

function connection(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    propertyId: 1,
    channelCode: 'AIRBNB_ICAL',
    adapterType: 'ICAL',
    displayName: '에어비앤비',
    syncEnabled: true,
    credentials: { ical_url: 'http••••1a2b' },
    capabilities: ['PULL_BOOKING'],
    ...overrides,
  };
}

let client: QueryClient;
let fetchMock: ReturnType<typeof vi.fn>;

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

function respond(url: string) {
  if (url.startsWith('/api/channels')) {
    return [connection(), connection({
      id: 2,
      channelCode: 'BOOKING_COM',
      adapterType: 'CHANNEX',
      displayName: '부킹닷컴',
      credentials: { api_key: 'chnx••••1a2b' },
      capabilities: ['PUSH_AVAILABILITY', 'PUSH_RATE', 'PUSH_RESTRICTION', 'PULL_BOOKING'],
    })];
  }
  return [{
    id: 1, name: '성수동 오피스텔', address: null, timezone: 'Asia/Seoul',
    currency: 'KRW', checkInTime: '15:00', checkOutTime: '11:00', status: 'ACTIVE',
  }];
}

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  tokenStore.set('test-token');
  resetRefreshState();
  fetchMock = vi.fn((url: string) =>
    Promise.resolve({
      ok: true,
      status: 200,
      json: () => Promise.resolve(respond(url)),
    } as Response),
  );
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
});

describe('채널 목록', () => {
  it('iCal 연결에 요금 전파 미지원이 보인다', async () => {
    render(<ChannelsPage />, { wrapper });

    // 어댑터 구현은 아직 하나도 없다. 그래도 화면은 옳은 것을 보여 줘야 한다.
    await waitFor(() => expect(screen.getByText('에어비앤비')).toBeInTheDocument());
    expect(screen.getAllByText('요금 전파 미지원')).toHaveLength(1);
    expect(screen.getAllByText('요금 전파 지원')).toHaveLength(1);
  });

  it('지원하지 않는 기능을 지우지 않고 미지원으로 남긴다', async () => {
    render(<ChannelsPage />, { wrapper });

    // 없는 항목은 눈에 띄지 않는다. 호스트가 "요금이 왜 안 넘어가지"를 뒤늦게 겪는다.
    await waitFor(() => expect(screen.getByText('에어비앤비')).toBeInTheDocument());
    expect(screen.getByText('재고 전파 미지원')).toBeInTheDocument();
    expect(screen.getByText('제약 전파 미지원')).toBeInTheDocument();
  });

  it('자격 증명은 마스킹된 형태로만 보인다', async () => {
    render(<ChannelsPage />, { wrapper });

    await waitFor(() => expect(screen.getByText('에어비앤비')).toBeInTheDocument());
    expect(document.body.textContent).toContain('••••');
  });

  it('수정 폼의 자격 증명 칸은 비어 있는 채로 시작한다', async () => {
    const { default: userEvent } = await import('@testing-library/user-event');
    render(<ChannelsPage />, { wrapper });

    await waitFor(() => expect(screen.getByText('에어비앤비')).toBeInTheDocument());
    await userEvent.click(screen.getAllByRole('button', { name: '수정' })[0]!);

    // 저장된 값을 받아오지 않으므로 채울 수가 없다. 비운 채로 저장하면 서버가 기존
    // 값을 유지한다. 마스킹된 값을 채워 넣으면 그게 그대로 저장된다.
    const input = screen.getByPlaceholderText('변경할 때만 입력') as HTMLInputElement;
    expect(input.value).toBe('');
    expect(input.type).toBe('password');
  });
});
