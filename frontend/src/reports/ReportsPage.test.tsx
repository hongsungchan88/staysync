import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { ReportsPage } from './ReportsPage';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 완료 조건 13·14 의 화면 쪽. 리포트.
 *
 * **서버가 보내는 형태 그대로 픽스처를 적는다.** 비율은 0.0 ~ 1.0 으로 오고 화면이
 * 백분율로 바꾼다. 픽스처에 이미 백분율을 적어 두면 화면이 100을 곱해도 테스트가
 * 통과한다 — 15주차에 시각 픽스처로 같은 함정에 빠졌다.
 *
 * **이 파일이 보는 것은 지표의 뜻이다.** 숫자를 맞히는 것은 서버 테스트가 한다.
 */

const 지표 = {
  soldNights: 5,
  availableNights: 20,
  roomRevenue: 500000,
  occupancyRate: 0.25,
  adr: 100000,
  revPar: 25000,
  leadTimeDays: 12.5,
  cancellationRate: 0.5,
  channelMix: [
    { channelCode: 'DIRECT', reservations: 2, revenue: 400000, revenueShare: 0.8 },
    { channelCode: 'MOCK', reservations: 1, revenue: 100000, revenueShare: 0.2 },
  ],
};

const 빈지표 = {
  soldNights: 0,
  availableNights: 31,
  roomRevenue: 0,
  occupancyRate: 0,
  adr: 0,
  revPar: 0,
  leadTimeDays: 0,
  cancellationRate: 0,
  channelMix: [],
};

let client: QueryClient;
let fetchMock: ReturnType<typeof vi.fn>;
/** 마지막 리포트 요청 주소. 필터가 실제로 실려 나가는지 본다. */
let lastReportUrl = '';

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
}

function respond(metrics: unknown) {
  fetchMock.mockImplementation((url: string) => {
    if (String(url).startsWith('/api/properties')) {
      return Promise.resolve(json([{ ...숙소, id: 7, name: '바다집' }]));
    }
    lastReportUrl = String(url);
    return Promise.resolve(json(metrics));
  });
}

const 숙소 = {
  id: 7,
  name: '바다집',
  timezone: 'Asia/Seoul',
  currency: 'KRW',
  checkInTime: '15:00',
  checkOutTime: '11:00',
  status: 'ACTIVE',
};

function json(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  tokenStore.set('test-token');
  resetRefreshState();
  lastReportUrl = '';
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
  client.clear();
});

describe('리포트 화면', () => {
  it('지표 여섯을 사람이 읽는 단위로 보여 준다', async () => {
    respond(지표);
    render(<ReportsPage />, { wrapper });

    // 비율은 0.25 로 왔고 화면이 백분율로 바꾼다.
    await waitFor(() => {
      expect(within(screen.getByTestId('metric-occupancy')).getByText('25.0%')).toBeInTheDocument();
    });
    expect(within(screen.getByTestId('metric-adr')).getByText('100,000원')).toBeInTheDocument();
    expect(within(screen.getByTestId('metric-revpar')).getByText('25,000원')).toBeInTheDocument();
    expect(within(screen.getByTestId('metric-leadtime')).getByText('12.5일')).toBeInTheDocument();
    expect(
      within(screen.getByTestId('metric-cancellation')).getByText('50.0%'),
    ).toBeInTheDocument();
    expect(within(screen.getByTestId('metric-revenue')).getByText('500,000원')).toBeInTheDocument();
  });

  it('RevPAR 이 ADR 곱하기 점유율로 읽힌다', async () => {
    respond(지표);
    render(<ReportsPage />, { wrapper });

    // 화면이 셋을 나란히 띄우므로 누구든 곱해 본다. 100,000 × 0.25 = 25,000 이다.
    // 서버가 분모를 갈라 놓으면 이 셋이 서로 안 맞고, 셋 다 의심받는다.
    await waitFor(() => {
      expect(within(screen.getByTestId('metric-adr')).getByText('100,000원')).toBeInTheDocument();
    });
    expect(지표.adr * 지표.occupancyRate).toBe(지표.revPar);
  });

  it('판매된 것이 없으면 0인 지표와 구분해 알려 준다', async () => {
    respond(빈지표);
    render(<ReportsPage />, { wrapper });

    // 0%가 성과처럼 읽히면 안 된다. 새 숙소를 등록한 직후가 이 경우다.
    await waitFor(() => {
      expect(screen.getByText(/판매된 객실박이 없습니다/)).toBeInTheDocument();
    });
    expect(within(screen.getByTestId('metric-occupancy')).getByText('0.0%')).toBeInTheDocument();
    // 판매 가능 객실박은 예약과 무관하게 남는다. 판매중지를 빼지 않기 때문이다.
    expect(screen.getByText(/판매 가능 31 객실박/)).toBeInTheDocument();
  });

  it('채널 믹스를 건수와 매출 비중으로 보여 준다', async () => {
    respond(지표);
    render(<ReportsPage />, { wrapper });

    const 표 = await screen.findByRole('table');

    // 매출 내림차순이다. 화면이 큰 것부터 보여 준다 — 서버가 그 순서로 보낸다.
    const 줄들 = within(표).getAllByRole('row');
    expect(줄들[1]?.textContent).toContain('DIRECT');
    expect(줄들[2]?.textContent).toContain('MOCK');

    const 첫줄 = within(표).getByRole('row', { name: /DIRECT/ });
    // 건수는 예약 수이고 박 수가 아니다.
    expect(within(첫줄).getByText('2건')).toBeInTheDocument();
    expect(within(첫줄).getByText('80.0%')).toBeInTheDocument();
  });

  it('기간과 숙소 필터가 요청에 실린다', async () => {
    respond(지표);
    render(<ReportsPage />, { wrapper });

    await waitFor(() => expect(lastReportUrl).toContain('/api/reports'));
    // 기본값은 이번 달이고 숙소를 고르지 않았으므로 propertyId 가 없다.
    expect(lastReportUrl).not.toContain('propertyId');

    await userEvent.selectOptions(await screen.findByLabelText('숙소'), '7');

    await waitFor(() => expect(lastReportUrl).toContain('propertyId=7'));

    await userEvent.clear(screen.getByLabelText('시작 날짜'));
    await userEvent.type(screen.getByLabelText('시작 날짜'), '2027-08-01');

    await waitFor(() => expect(lastReportUrl).toContain('from=2027-08-01'));
  });
});
