import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ReservationPanel } from './ReservationPanel';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';
import type { ReservationBar } from '@/api/schemas';

/**
 * 체크인·체크아웃 패널. 작업지시-15 2절 E.
 *
 * 화면이 하는 일은 전이 엔드포인트를 부르고 응답의 상태를 보여 주는 것뿐이다.
 * 전이가 되는지는 서버가 판정하므로, 여기서 지킬 것은 **어느 경로를 부르는지**와
 * **거절 이유가 화면에 남는지**다.
 */

const KEY = ['calendar', 1, '2027-03-01', '2027-03-30'] as const;

function bar(status: string): ReservationBar {
  return {
    id: 73,
    unitId: 3,
    checkIn: '2027-03-05',
    checkOut: '2027-03-07',
    guestName: '김도현',
    channel: 'BOOKING_COM',
    status,
    amount: 360000,
  };
}

/** 서버가 실제로 보내는 형태다(`ReservationSummary`). */
function summary(status: string) {
  return {
    id: 73,
    propertyId: 1,
    unitId: 3,
    status,
    checkIn: '2027-03-05',
    checkOut: '2027-03-07',
    nights: 2,
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

describe('체크인·체크아웃 패널', () => {
  it('확정 예약에는 체크인만, 체크인한 예약에는 체크아웃만 보인다', () => {
    const { unmount } = render(
      <ReservationPanel bar={bar('CONFIRMED')} calendarKey={KEY} onClose={() => {}} />,
      { wrapper },
    );
    expect(screen.getByTestId('check-in-button')).toBeInTheDocument();
    expect(screen.queryByTestId('check-out-button')).not.toBeInTheDocument();

    // SelectionPanel 이 막대마다 key 를 달아 새로 만든다. 그 전제를 여기서도 따른다.
    unmount();
    render(<ReservationPanel bar={bar('CHECKED_OUT')} calendarKey={KEY} onClose={() => {}} />, {
      wrapper,
    });
    // 이미 끝난 예약에는 누를 것이 없다. 눌러도 안 되는 버튼을 두지 않는다.
    expect(screen.queryByTestId('check-in-button')).not.toBeInTheDocument();
    expect(screen.queryByTestId('check-out-button')).not.toBeInTheDocument();
  });

  it('체크아웃을 누르면 check-out 경로를 부르고 응답의 상태를 보여 준다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, summary('CHECKED_OUT')));
    render(<ReservationPanel bar={bar('CHECKED_IN')} calendarKey={KEY} onClose={() => {}} />, {
      wrapper,
    });

    fireEvent.click(screen.getByTestId('check-out-button'));
    // 누르기만 해서는 아무것도 나가지 않는다. 확인 단계가 먼저다.
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('transition-confirm')).toHaveTextContent('2027-03-05 ~ 2027-03-07');
    expect(screen.getByTestId('transition-confirm')).toHaveTextContent('청소 태스크');
    expect(screen.getByTestId('transition-confirm')).toHaveTextContent('인박스');
    fireEvent.click(screen.getByTestId('transition-confirm-button'));

    await waitFor(() =>
      expect(screen.getByTestId('reservation-status')).toHaveTextContent('체크아웃'),
    );
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/reservations/73/check-out');
    expect(fetchMock.mock.calls[0]![1].method).toBe('POST');
    // 끝났으니 버튼이 사라진다. 두 번 누르면 서버가 거절하겠지만 그 전에 화면이 막는다.
    expect(screen.queryByTestId('check-out-button')).not.toBeInTheDocument();
  });

  it('서버가 거절하면 이유를 보여 주고 상태를 바꾸지 않는다', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(409, { code: 'ILLEGAL_TRANSITION', message: '체크인 상태가 아닙니다.' }),
    );
    render(<ReservationPanel bar={bar('CONFIRMED')} calendarKey={KEY} onClose={() => {}} />, {
      wrapper,
    });

    fireEvent.click(screen.getByTestId('check-in-button'));
    fireEvent.click(screen.getByTestId('transition-confirm-button'));

    await waitFor(() =>
      expect(screen.getByTestId('reservation-error')).toHaveTextContent('체크인 상태가 아닙니다.'),
    );
    expect(screen.getByTestId('reservation-status')).toHaveTextContent('확정');
    expect(screen.getByTestId('check-in-button')).toBeInTheDocument();
  });

  // --- 작업지시-20 B·E -----------------------------------------------------

  it('iCal 예약은 이름이 없는 이유를 적고 금액은 미상으로 쓴다', () => {
    render(
      <ReservationPanel
        bar={{ ...bar('CONFIRMED'), guestName: undefined, channel: 'AIRBNB_ICAL', amount: undefined }}
        calendarKey={KEY}
        onClose={() => {}}
      />,
      { wrapper },
    );
    expect(screen.getByText('이름 없음(iCal)')).toBeInTheDocument();
    expect(screen.getByTestId('reservation-name-reason')).toHaveTextContent('게스트 이름');
    // 모르는 금액을 0원으로 쓰지 않는다(작업지시-16).
    expect(screen.getByTestId('reservation-amount')).toHaveTextContent('미상');
    expect(screen.queryByTestId('reservation-guests')).not.toBeInTheDocument();
  });

  it('iCal 이 아닌 예약에는 이유 문구가 없고, 이름이 없어도 붙지 않는다', () => {
    render(
      <ReservationPanel
        bar={{ ...bar('CONFIRMED'), guestName: undefined, channel: 'DIRECT' }}
        calendarKey={KEY}
        onClose={() => {}}
      />,
      { wrapper },
    );
    expect(screen.getByText('이름 없음')).toBeInTheDocument();
    expect(screen.queryByTestId('reservation-name-reason')).not.toBeInTheDocument();
  });

  it('금액과 인원이 있으면 보인다', () => {
    render(
      <ReservationPanel
        bar={{ ...bar('CONFIRMED'), channel: 'DIRECT', adults: 2, children: 1 }}
        calendarKey={KEY}
        onClose={() => {}}
      />,
      { wrapper },
    );
    expect(screen.getByTestId('reservation-amount')).toHaveTextContent('360,000원');
    expect(screen.getByTestId('reservation-guests')).toHaveTextContent('성인 2 · 아동 1');
  });

  // --- 작업지시-20 9절 ------------------------------------------------------

  it('확인 단계에서 취소하면 아무것도 보내지 않는다', () => {
    const confirmSpy = vi.spyOn(window, 'confirm');
    render(<ReservationPanel bar={bar('CONFIRMED')} calendarKey={KEY} onClose={() => {}} />, {
      wrapper,
    });

    fireEvent.click(screen.getByTestId('check-in-button'));
    fireEvent.click(screen.getByRole('button', { name: '취소' }));

    expect(screen.queryByTestId('transition-confirm')).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(confirmSpy).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it('체크인 취소는 사유가 있어야 보낼 수 있고, 사유를 실어 undo 경로를 부른다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, summary('CONFIRMED')));
    render(<ReservationPanel bar={bar('CHECKED_IN')} calendarKey={KEY} onClose={() => {}} />, {
      wrapper,
    });

    fireEvent.click(screen.getByTestId('undo-check-in-button'));
    expect(screen.getByTestId('transition-confirm-button')).toBeDisabled();
    fireEvent.change(screen.getByTestId('undo-reason'), { target: { value: ' 잘못 눌렀다 ' } });
    fireEvent.click(screen.getByTestId('transition-confirm-button'));

    await waitFor(() => expect(screen.getByTestId('reservation-status')).toHaveTextContent('확정'));
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/reservations/73/check-in/undo');
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({ reason: '잘못 눌렀다' });
    // 확정으로 돌아왔으니 다시 체크인할 수 있다.
    expect(screen.getByTestId('check-in-button')).toBeInTheDocument();
  });

  it('체크아웃 되돌리기는 퇴실일이 지나면 버튼이 없다', () => {
    const { unmount } = render(
      <ReservationPanel
        bar={{ ...bar('CHECKED_OUT'), checkIn: '2020-01-01', checkOut: '2020-01-03' }}
        calendarKey={KEY}
        onClose={() => {}}
      />,
      { wrapper },
    );
    expect(screen.queryByTestId('undo-check-out-button')).not.toBeInTheDocument();
    unmount();

    render(<ReservationPanel bar={bar('CHECKED_OUT')} calendarKey={KEY} onClose={() => {}} />, {
      wrapper,
    });
    expect(screen.getByTestId('undo-check-out-button')).toBeInTheDocument();
  });

  it('청소가 시작돼 서버가 거절하면 그 이유를 보여 주고 상태를 바꾸지 않는다', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse(409, {
        code: 'CLEANING_ALREADY_STARTED',
        message: "청소 태스크가 이미 '진행 중' 상태라 체크아웃을 되돌릴 수 없습니다.",
      }),
    );
    render(<ReservationPanel bar={bar('CHECKED_OUT')} calendarKey={KEY} onClose={() => {}} />, {
      wrapper,
    });

    fireEvent.click(screen.getByTestId('undo-check-out-button'));
    fireEvent.change(screen.getByTestId('undo-reason'), { target: { value: '손님이 아직 있다' } });
    fireEvent.click(screen.getByTestId('transition-confirm-button'));

    await waitFor(() =>
      expect(screen.getByTestId('reservation-error')).toHaveTextContent('진행 중'),
    );
    expect(fetchMock.mock.calls[0]![0]).toBe('/api/reservations/73/check-out/undo');
    expect(screen.getByTestId('reservation-status')).toHaveTextContent('체크아웃');
  });
});
