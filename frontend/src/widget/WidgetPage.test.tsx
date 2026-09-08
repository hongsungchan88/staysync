import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { WidgetPage } from './WidgetPage';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 완료 조건 4·5·6 의 화면 쪽. 직접예약 위젯.
 *
 * **이 파일이 가장 신경 쓰는 것은 토큰이 나가지 않는 것이다.** 공개 화면이 인증
 * 경로를 타면 세션 없는 방문자에게 갱신이 돌고, 실패한 갱신이 **호스트의 다른 탭을
 * 로그아웃시킨다.** 증상이 위젯이 아니라 관리 화면에서 나타나 원인을 찾기 어렵다.
 *
 * 픽스처는 서버가 실제로 보내는 형태다 — 금액은 숫자이고 날짜는 `YYYY-MM-DD` 다.
 */

const 체크인 = '2027-08-05';
const 체크아웃 = '2027-08-07';

/** 2박. 서버는 마지막 밤까지만 돌려준다 — 체크아웃일은 재고를 차지하지 않는다. */
const 객실 = {
  id: 11,
  name: '본채',
  days: [
    { date: '2027-08-05', available: 1, price: 100000, minStay: 1, stopSell: false },
    { date: '2027-08-06', available: 1, price: 100000, minStay: 1, stopSell: false },
  ],
};

let client: QueryClient;
let fetchMock: ReturnType<typeof vi.fn>;
/** 마지막 홀드 요청. 서버로 실제로 나간 값을 본다. */
let lastHold: { url: string; init?: RequestInit } | null = null;

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/widget/7']}>
        <Routes>
          <Route path="/widget/:propertyId" element={children} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function json(body: unknown, status = 200): Response {
  return {
    ok: status < 400,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

/** 예약 상태. 결제 확인 폴링이 이 값을 읽는다. */
let 예약상태 = 'HOLD';
/** 결제창에 넘어간 값. 서버가 준 것을 그대로 쓰는지 본다. */
let 결제요청: Record<string, unknown> | null = null;

/** 가용은 units 로, 홀드는 holdResponse 로 답한다. 결제 경로는 기본값으로 답한다. */
function serve(units: unknown, holdResponse: () => Response) {
  fetchMock.mockImplementation((url: string, init?: RequestInit) => {
    const path = String(url);
    if (path.includes('/availability')) {
      return Promise.resolve(json(units));
    }
    if (path.includes('/payments/prepare')) {
      return Promise.resolve(
        json({
          paymentId: 'staysync-abc',
          storeId: 'store-test',
          channelKey: 'channel-test',
          amount: 200000,
          orderName: '숙박 2027-08-05 ~ 2027-08-07',
        }),
      );
    }
    if (path.includes('/payments/status/')) {
      return Promise.resolve(json({ status: 예약상태 }));
    }
    lastHold = { url: path, init };
    return Promise.resolve(holdResponse());
  });
}

/** 결제창을 흉내 낸다. 실제 SDK 는 남의 스크립트라 테스트에서 부를 수 없다. */
function 결제창(result: { code?: string; message?: string } | undefined) {
  window.PortOne = {
    requestPayment: (request: Record<string, unknown>) => {
      결제요청 = request;
      return Promise.resolve(result);
    },
  } as unknown as typeof window.PortOne;
}

/** 홀드까지 간다. 결제 화면이 뜬 상태로 끝난다. */
async function 홀드까지() {
  await 날짜를_고른다();
  await userEvent.click(await screen.findByRole('radio'));
  await userEvent.type(screen.getByLabelText('이름'), '김손님');
  await userEvent.click(screen.getByRole('button', { name: '예약 잡기' }));
  await screen.findByText('ABC12345');
}

const 홀드성공 = () =>
  json({
    reservationId: 501,
    confirmationCode: 'ABC12345',
    amount: 200000,
    expiresAt: '2027-08-01T02:15:00Z',
  });

async function 날짜를_고른다() {
  await userEvent.type(screen.getByLabelText('체크인'), 체크인);
  await userEvent.type(screen.getByLabelText('체크아웃'), 체크아웃);
}

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  lastHold = null;
  예약상태 = 'HOLD';
  결제요청 = null;
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  delete window.PortOne;
  tokenStore.clear();
  client.clear();
});

describe('직접예약 위젯', () => {
  it('로그인 없이 가용과 요금을 보여 준다', async () => {
    serve([객실], 홀드성공);
    render(<WidgetPage />, { wrapper });

    await 날짜를_고른다();

    // 2박 20만원. 화면은 서버가 준 날짜별 요금을 더하기만 한다.
    expect(await screen.findByText('2박 200,000원')).toBeInTheDocument();
  });

  it('요청에 액세스 토큰을 싣지 않는다', async () => {
    // 호스트가 같은 브라우저에서 로그인해 둔 상황이다. 위젯은 그 토큰을 쓰면 안 된다 —
    // 공개 경로이고, 401 갱신이 돌면 호스트의 다른 탭이 로그아웃된다.
    tokenStore.set('호스트-토큰');
    serve([객실], 홀드성공);
    render(<WidgetPage />, { wrapper });

    await 날짜를_고른다();
    await screen.findByText('2박 200,000원');

    for (const call of fetchMock.mock.calls) {
      const headers = (call[1]?.headers ?? {}) as Record<string, string>;
      expect(headers['Authorization']).toBeUndefined();
    }
  });

  it('팔 수 없는 날짜가 끼면 고를 수 없고 이유를 보여 준다', async () => {
    const 판매중지 = {
      ...객실,
      days: [객실.days[0], { ...객실.days[1], stopSell: true }],
    };
    serve([판매중지], 홀드성공);
    render(<WidgetPage />, { wrapper });

    await 날짜를_고른다();

    // 비활성만 해 두면 손님이 날짜를 바꿔 볼 생각을 못 한다.
    expect(await screen.findByText('예약할 수 없는 날짜가 있습니다')).toBeInTheDocument();
    expect(screen.getByRole('radio')).toBeDisabled();
  });

  it('최소 숙박일보다 짧으면 고를 수 없다', async () => {
    const 최소3박 = {
      ...객실,
      days: 객실.days.map((day) => ({ ...day, minStay: 3 })),
    };
    serve([최소3박], 홀드성공);
    render(<WidgetPage />, { wrapper });

    await 날짜를_고른다();

    expect(await screen.findByText('최소 3박부터 예약할 수 있습니다')).toBeInTheDocument();
  });

  it('서버가 계산한 합계와 고른 인원을 그대로 보낸다', async () => {
    serve([객실], 홀드성공);
    render(<WidgetPage />, { wrapper });

    await 날짜를_고른다();
    await userEvent.click(await screen.findByRole('radio'));
    await userEvent.clear(screen.getByLabelText('아동'));
    await userEvent.type(screen.getByLabelText('아동'), '1');
    await userEvent.type(screen.getByLabelText('이름'), '김손님');
    await userEvent.click(screen.getByRole('button', { name: '예약 잡기' }));

    await waitFor(() => expect(lastHold).not.toBeNull());
    const 보낸것 = JSON.parse(String(lastHold?.init?.body));
    // quotedAmount 는 대조용이다. 서버가 이 값으로 예약을 만들지 않는다.
    expect(보낸것.quotedAmount).toBe(200000);
    // 인원을 보내지 않으면 예약 기본값(성인 2)이 박혀 호스트가 보는 인원이 늘 2명이 된다.
    expect(보낸것.adults).toBe(2);
    expect(보낸것.children).toBe(1);
    expect(보낸것.checkOut).toBe(체크아웃);
  });

  it('홀드가 되면 확인번호와 결제 마감 시각을 보여 준다', async () => {
    serve([객실], 홀드성공);
    render(<WidgetPage />, { wrapper });

    await 날짜를_고른다();
    await userEvent.click(await screen.findByRole('radio'));
    await userEvent.type(screen.getByLabelText('이름'), '김손님');
    await userEvent.click(screen.getByRole('button', { name: '예약 잡기' }));

    expect(await screen.findByText('ABC12345')).toBeInTheDocument();
    // 15분 뒤 사라지는 점유다. 마감을 모르면 자리를 비운 사이 예약이 조용히 없어진다.
    expect(screen.getByText(/까지 결제해야 합니다/)).toBeInTheDocument();
  });

  it('서버가 금액 불일치로 거절하면 그 이유를 보여 준다', async () => {
    serve([객실], () =>
      json({ code: 'QUOTE_MISMATCH', message: '요금이 변경되었습니다. 다시 확인해 주세요.', details: [] }, 409),
    );
    render(<WidgetPage />, { wrapper });

    await 날짜를_고른다();
    await userEvent.click(await screen.findByRole('radio'));
    await userEvent.type(screen.getByLabelText('이름'), '김손님');
    await userEvent.click(screen.getByRole('button', { name: '예약 잡기' }));

    // 화면이 낡은 요금을 들고 있었다는 뜻이다. 조용히 넘어가면 결제 금액과 예약
    // 금액이 다른 예약이 생긴다.
    expect(await screen.findByRole('alert')).toHaveTextContent('요금이 변경되었습니다');
    expect(screen.queryByText('ABC12345')).not.toBeInTheDocument();
  });

  it('결제창에 서버가 준 값을 그대로 넘긴다', async () => {
    serve([객실], 홀드성공);
    결제창(undefined);
    render(<WidgetPage />, { wrapper });
    await 홀드까지();

    await userEvent.click(screen.getByRole('button', { name: '결제하기' }));

    await waitFor(() => expect(결제요청).not.toBeNull());
    // 결제 식별자는 서버가 만든다. 화면이 만들면 같은 값을 두 번 쓰거나 남의 것을
    // 지어낼 수 있다.
    expect(결제요청?.paymentId).toBe('staysync-abc');
    expect(결제요청?.storeId).toBe('store-test');
    expect(결제요청?.totalAmount).toBe(200000);
  });

  it('결제창이 성공해도 서버가 확정하기 전에는 완료로 보여 주지 않는다', async () => {
    serve([객실], 홀드성공);
    결제창(undefined);
    render(<WidgetPage />, { wrapper });
    await 홀드까지();

    // 서버는 아직 HOLD 다. 웹훅이 도착하지 않았다.
    await userEvent.click(screen.getByRole('button', { name: '결제하기' }));

    expect(await screen.findByText('결제를 확인하는 중입니다…')).toBeInTheDocument();
    // 결제창의 성공만 믿고 완료를 띄우면, 웹훅이 끝내 오지 않을 때 15분 뒤 조용히
    // 사라질 예약을 확정으로 보여 준 것이 된다.
    expect(screen.queryByText('예약이 확정되었습니다')).not.toBeInTheDocument();
  });

  it('서버가 확정하면 그때 완료로 바뀐다', async () => {
    serve([객실], 홀드성공);
    결제창(undefined);
    render(<WidgetPage />, { wrapper });
    await 홀드까지();

    예약상태 = 'CONFIRMED';
    await userEvent.click(screen.getByRole('button', { name: '결제하기' }));

    expect(await screen.findByText('예약이 확정되었습니다', undefined, { timeout: 5000 }))
      .toBeInTheDocument();
  });

  it('결제창이 실패하면 이유를 보여 주고 다시 시도할 수 있다', async () => {
    serve([객실], 홀드성공);
    결제창({ code: 'PAY_PROCESS_CANCELED', message: '결제를 취소했습니다.' });
    render(<WidgetPage />, { wrapper });
    await 홀드까지();

    await userEvent.click(screen.getByRole('button', { name: '결제하기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('결제를 취소했습니다');
    // 버튼이 남아 있어야 다시 시도할 수 있다.
    expect(screen.getByRole('button', { name: '결제하기' })).toBeEnabled();
  });
});
