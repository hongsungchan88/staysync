import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { useReservationMove } from './useReservationMove';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';
import type { CalendarGrid } from '@/api/schemas';

/**
 * 완료 조건 6. **낙관적 업데이트가 서버 판정을 우회하지 않는다.**
 *
 * 이번 주에서 가장 중요한 테스트다. 놓는 순간 화면을 먼저 옮기는 것이 낙관적 업데이트인데,
 * 서버 실패를 삼키면 화면에는 옮겨진 것처럼 보이고 원장은 그대로인 상태가 남는다. 그게
 * 이 프로젝트가 막으려는 중복예약이 생기는 모습이다 — 화면만 보면 정상이고, 드러나는 것은
 * 다른 채널 예약이 들어온 뒤다.
 *
 * 5~6주차 트랜잭션 경계와 같은 성질이라, 실패해도 눈에 띄지 않는다는 것이 핵심이다.
 */

const KEY = ['calendar', 1, '2026-09-01', '2026-11-29'] as const;

function gridWith(checkIn: string, checkOut: string): CalendarGrid {
  return {
    from: '2026-09-01',
    to: '2026-11-29',
    units: [
      {
        id: 1,
        name: '객실 01',
        totalUnits: 1,
        days: [
          {
            date: '2026-09-01',
            avail: 1,
            price: 120000,
            minStay: 1,
            stopSell: false,
            conflict: false,
          },
        ],
      },
    ],
    reservations: [
      {
        id: 7,
        unitId: 1,
        checkIn,
        checkOut,
        guestName: '김손님',
        channel: 'DIRECT',
        status: 'CONFIRMED',
        amount: 240000,
      },
    ],
  };
}

let client: QueryClient;
let fetchMock: ReturnType<typeof vi.fn>;

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function jsonResponse(status: number, body: unknown) {
  return {
    ok: status < 400,
    status,
    json: async () => body,
  };
}

function barOf(): { checkIn: string; checkOut: string } {
  const data = client.getQueryData<CalendarGrid>(KEY)!;
  const bar = data.reservations[0]!;
  return { checkIn: bar.checkIn, checkOut: bar.checkOut };
}

beforeEach(() => {
  resetRefreshState();
  tokenStore.set('테스트-토큰');
  client = new QueryClient({
    // 실패를 되돌리는지 보는 테스트다. 자동 재시도가 있으면 그 지점이 흐려진다.
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  client.setQueryData(KEY, gridWith('2026-09-10', '2026-09-13'));
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
  resetRefreshState();
});

describe('예약 막대 옮기기', () => {
  it('서버가 거절하면 막대가 원위치로 돌아오고 이유가 나온다', async () => {
    // 응답을 손으로 풀어 준다. 그러지 않으면 되돌리기가 너무 빨라, 화면이 먼저 옮겨졌다는
    // 사실을 관찰할 수 없다. 사용자가 보는 순서까지 확인하는 것이 이 테스트의 요지다.
    let release = () => {};
    const pending = new Promise<void>((resolve) => {
      release = resolve;
    });
    // InventoryService 가 재고 부족으로 막는 응답. chk_no_oversell 까지 가지 않는다.
    fetchMock.mockImplementation(async () => {
      await pending;
      return jsonResponse(409, {
        code: 'INVENTORY_INSUFFICIENT',
        message: '2026-09-12 에 판매 가능한 재고가 없습니다. (사유: INSUFFICIENT)',
        details: [],
      });
    });

    const rejections: string[] = [];
    const { result } = renderHook(() => useReservationMove(KEY, (r) => rejections.push(r)), {
      wrapper,
    });

    result.current.move({ reservationId: 7, dayDelta: 2 });

    // 먼저 옮겨 보인다. 이게 낙관적 업데이트다.
    await waitFor(() => expect(barOf().checkIn).toBe('2026-09-12'));

    release();
    await waitFor(() => expect(result.current.isError).toBe(true));

    // 그리고 되돌아와야 한다. 여기가 이 테스트의 전부다.
    expect(barOf()).toEqual({ checkIn: '2026-09-10', checkOut: '2026-09-13' });
    // 왜 안 됐는지도 보여야 한다. 조용히 되돌아가면 사용자는 드래그가 안 먹혔다고 본다.
    expect(rejections).toHaveLength(1);
    expect(rejections[0]).toContain('재고가 없습니다');
  });

  it('옮길 수 없는 상태면 전이 오류를 그대로 보여 준다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, {
        code: 'ILLEGAL_RESERVATION_TRANSITION',
        message: 'CANCELLED 상태에서 CANCELLED 로 바꿀 수 없습니다.',
        details: [],
      }),
    );

    const rejections: string[] = [];
    const { result } = renderHook(() => useReservationMove(KEY, (r) => rejections.push(r)), {
      wrapper,
    });

    result.current.move({ reservationId: 7, dayDelta: 1 });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(barOf().checkIn).toBe('2026-09-10');
    expect(rejections[0]).toContain('바꿀 수 없습니다');
  });

  it('숙박 일수를 유지한 채 옮긴다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, {
        id: 7,
        propertyId: 1,
        unitId: 1,
        status: 'CONFIRMED',
        checkIn: '2026-09-13',
        checkOut: '2026-09-16',
        nights: 3,
      }),
    );

    const { result } = renderHook(() => useReservationMove(KEY, () => {}), { wrapper });

    result.current.move({ reservationId: 7, dayDelta: 3 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // 양끝을 같은 만큼 민다. 길이를 바꾸는 것은 이번 범위가 아니다(3절).
    const [, options] = fetchMock.mock.calls[0]!;
    expect(JSON.parse(options.body)).toEqual({
      checkIn: '2026-09-13',
      checkOut: '2026-09-16',
    });
  });

  it('성공해도 캘린더를 다시 불러온다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, {
        id: 7,
        propertyId: 1,
        unitId: 1,
        status: 'CONFIRMED',
        checkIn: '2026-09-11',
        checkOut: '2026-09-14',
        nights: 3,
      }),
    );
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useReservationMove(KEY, () => {}), { wrapper });
    result.current.move({ reservationId: 7, dayDelta: 1 });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    // 옮기면 옛 날짜와 새 날짜의 잔여 재고가 함께 바뀐다. 낙관적 업데이트는 막대만
    // 옮겼으므로, 다시 불러오지 않으면 셀의 숫자가 원장과 어긋난 채 남는다.
    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ queryKey: KEY })),
    );
  });
});
