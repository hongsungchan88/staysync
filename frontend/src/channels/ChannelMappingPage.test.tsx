import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ChannelMappingPage } from './ChannelMappingPage';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 매핑 화면.
 *
 * **매핑되지 않은 판매 단위가 눈에 띄어야 한다**는 것이 여기서 지킬 계약이다.
 * 매핑이 없으면 그 단위는 이 채널에 나가지 않는데, 목록에서 조용히 빠져 있으면
 * 호스트는 예약이 들어오지 않는 이유를 알 수 없다.
 */

const BOARD = {
  connection: {
    id: 7,
    propertyId: 1,
    channelCode: 'BOOKING_COM',
    adapterType: 'CHANNEX',
    displayName: '부킹닷컴',
    syncEnabled: true,
    credentials: { api_key: 'chnx••••1a2b' },
    capabilities: ['PUSH_AVAILABILITY', 'PUSH_RATE'],
  },
  units: [
    {
      unitId: 1,
      unitName: '본채',
      mapping: { id: 11, unitId: 1, externalUnitId: 'room_type_1', externalRateId: null },
    },
    // 서버가 non_null 로 직렬화하므로 매핑되지 않은 단위에는 mapping 키가 아예 없다.
    { unitId: 2, unitName: '별채' },
  ],
};

let client: QueryClient;

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/channels/7/mapping']}>
        <Routes>
          <Route path="/channels/:connectionId/mapping" element={children} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  tokenStore.set('test-token');
  resetRefreshState();
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(BOARD),
      } as Response),
    ),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
});

describe('채널 매핑', () => {
  it('매핑되지 않은 판매 단위를 표시한다', async () => {
    render(<ChannelMappingPage />, { wrapper });

    await waitFor(() => expect(screen.getByText('본채')).toBeInTheDocument());
    expect(screen.getByText('별채')).toBeInTheDocument();
    expect(screen.getByText('매핑되지 않음')).toBeInTheDocument();
    expect(
      screen.getByText(/매핑되지 않은 판매 단위가 1개 있습니다/),
    ).toBeInTheDocument();
  });

  it('매핑된 단위는 채널 쪽 식별자를 보여 준다', async () => {
    render(<ChannelMappingPage />, { wrapper });

    await waitFor(() => expect(screen.getByText('room_type_1')).toBeInTheDocument());
  });

  it('매핑되지 않은 단위에만 입력 칸이 열린다', async () => {
    render(<ChannelMappingPage />, { wrapper });

    await waitFor(() => expect(screen.getByText('본채')).toBeInTheDocument());
    // Channex 는 room_type_id 를 요구한다. 채널마다 식별자 모양이 다르므로 안내한다.
    expect(screen.getAllByPlaceholderText('room_type_id')).toHaveLength(1);
  });
});
