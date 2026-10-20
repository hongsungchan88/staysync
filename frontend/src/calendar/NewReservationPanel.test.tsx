import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { NewReservationPanel } from './NewReservationPanel';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';
import type { CalendarGrid } from '@/api/schemas';

/** 작업지시-28 B — 예약 추가 폼이 요청을 올바르게 만들고, 서버 문구를 그대로 보인다. */

const KEY = ['calendar', 7, '2027-03-01', '2027-03-30'] as const;

function grid(): CalendarGrid {
  return {
    from: '2027-03-01',
    to: '2027-03-30',
    units: [
      { id: 11, name: '본채', totalUnits: 1, days: [] },
      { id: 12, name: '별채', totalUnits: 1, days: [] },
    ],
    reservations: [],
  };
}

function jsonResponse(status: number, body: unknown) {
  return { ok: status < 400, status, json: async () => body };
}

let client: QueryClient;
let fetchMock: ReturnType<typeof vi.fn>;

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function renderPanel() {
  return render(
    <NewReservationPanel data={grid()} propertyId={7} calendarKey={KEY} onClose={() => {}} />,
    { wrapper },
  );
}

function fill(amount: string) {
  fireEvent.change(screen.getByTestId('new-reservation-unit'), { target: { value: '12' } });
  fireEvent.change(screen.getByTestId('new-reservation-check-in'), { target: { value: '2027-03-05' } });
  fireEvent.change(screen.getByTestId('new-reservation-check-out'), { target: { value: '2027-03-07' } });
  fireEvent.change(screen.getByTestId('new-reservation-guest'), { target: { value: ' 전화 손님 ' } });
  fireEvent.change(screen.getByTestId('new-reservation-adults'), { target: { value: '3' } });
  fireEvent.change(screen.getByTestId('new-reservation-children'), { target: { value: '1' } });
  fireEvent.change(screen.getByTestId('new-reservation-amount'), { target: { value: amount } });
}

function summary(totalAmount: number) {
  return {
    id: 501, propertyId: 7, unitId: 12, status: 'CONFIRMED', channelCode: 'DIRECT',
    checkIn: '2027-03-05', checkOut: '2027-03-07', nights: 2, totalAmount,
  };
}

beforeEach(() => {
  resetRefreshState();
  tokenStore.set('테스트-토큰');
  client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
  resetRefreshState();
});

describe('예약 추가', () => {
  it('금액을 비우면 totalAmount null 로 보내고, 확인 단계 뒤에만 보낸다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, summary(200000)));
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    renderPanel();
    fill('');

    fireEvent.click(screen.getByTestId('new-reservation-submit'));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('new-reservation-confirm')).toHaveTextContent('요금표로 계산합니다');

    fireEvent.click(screen.getByTestId('new-reservation-confirm-button'));
    await waitFor(() => expect(screen.getByTestId('new-reservation-done')).toHaveTextContent('200,000원'));

    expect(fetchMock.mock.calls[0]![0]).toBe('/api/reservations');
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({
      propertyId: 7, unitId: 12, checkIn: '2027-03-05', checkOut: '2027-03-07',
      totalAmount: null, adults: 3, children: 1, guestName: '전화 손님',
    });
    expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ queryKey: KEY }));
  });

  it('금액을 적으면 그 값을 보낸다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(201, summary(150000)));
    renderPanel();
    fill('150000');

    fireEvent.click(screen.getByTestId('new-reservation-submit'));
    expect(screen.getByTestId('new-reservation-confirm')).toHaveTextContent('150,000원입니다');
    fireEvent.click(screen.getByTestId('new-reservation-confirm-button'));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body).totalAmount).toBe(150000);
  });

  it('서버가 거절하면 그 문구를 그대로 보인다', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(409, { code: 'MIN_STAY_NOT_MET', message: '최소 2박부터 예약할 수 있습니다.', details: [] }),
    );
    renderPanel();
    fill('');

    fireEvent.click(screen.getByTestId('new-reservation-submit'));
    fireEvent.click(screen.getByTestId('new-reservation-confirm-button'));

    await waitFor(() =>
      expect(screen.getByTestId('new-reservation-error')).toHaveTextContent('최소 2박부터 예약할 수 있습니다.'),
    );
    expect(screen.queryByTestId('new-reservation-done')).not.toBeInTheDocument();
  });

  it('날짜가 거꾸로이거나 이름이 없으면 보내지 않는다', () => {
    renderPanel();
    fill('');
    fireEvent.change(screen.getByTestId('new-reservation-check-out'), { target: { value: '2027-03-05' } });
    expect(screen.getByTestId('new-reservation-problem')).toHaveTextContent('체크아웃은 체크인보다 뒤여야 합니다.');
    expect(screen.getByTestId('new-reservation-submit')).toBeDisabled();

    fireEvent.change(screen.getByTestId('new-reservation-check-out'), { target: { value: '2027-03-07' } });
    fireEvent.change(screen.getByTestId('new-reservation-guest'), { target: { value: ' ' } });
    expect(screen.getByTestId('new-reservation-problem')).toHaveTextContent('게스트 이름');
    expect(screen.getByTestId('new-reservation-submit')).toBeDisabled();
  });
});
