import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { useCalendarStream } from './useCalendarStream';
import type { CalendarEvent } from '@/api/calendarStream';

/**
 * 실시간 갱신이 조회를 부르는 방식.
 *
 * **사건 하나에 조회 하나로 두면 안 된다.** 사건은 몰려서 온다 — 앱이 꺼져 있다가
 * 켜지면 릴레이가 밀린 이벤트를 주기당 100건씩 쏟아낸다. 그때마다 2,700셀을 다시
 * 받으면 브라우저가 `ERR_INSUFFICIENT_RESOURCES` 로 죽는다. 실제로 그렇게 죽는 것을
 * 보고 넣은 묶기이고, 이 테스트가 그 묶기를 지킨다.
 */

const KEY = ['calendar', 1, '2027-03-01', '2027-03-30'] as const;

/** 구독 대신 끼워 넣어 사건을 손으로 흘려보낸다. */
let handlers: { onEvent: (e: CalendarEvent) => void; onReconnect: () => void } | null = null;
const unsubscribe = vi.fn();

vi.mock('@/api/calendarStream', () => ({
  subscribeCalendar: (options: {
    onEvent: (e: CalendarEvent) => void;
    onReconnect: () => void;
  }) => {
    handlers = options;
    return unsubscribe;
  },
}));

let client: QueryClient;

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

beforeEach(() => {
  handlers = null;
  unsubscribe.mockClear();
  client = new QueryClient();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('캘린더 실시간 갱신', () => {
  it('사건 하나가 오면 곧바로 다시 조회한다', async () => {
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    renderHook(() => useCalendarStream(KEY, 1), { wrapper });

    handlers!.onEvent({ type: 'RESERVATION_CONFIRMED', propertyId: 1 });

    // "즉시 반영"이 요구사항이다. 묶는다고 첫 사건을 늦추면 안 된다.
    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ queryKey: KEY })),
    );
    expect(invalidate).toHaveBeenCalledTimes(1);
  });

  it('사건이 몰려 와도 조회가 그만큼 늘지 않는다', async () => {
    vi.useFakeTimers();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    renderHook(() => useCalendarStream(KEY, 1), { wrapper });

    // 밀린 이벤트가 한꺼번에 쏟아지는 상황. 릴레이는 주기당 100건까지 낸다.
    for (let i = 0; i < 200; i++) {
      handlers!.onEvent({ type: 'RESERVATION_CONFIRMED', propertyId: 1 });
    }

    // 첫 사건 하나만 나갔어야 한다. 200번 조회하면 브라우저가 죽는다.
    expect(invalidate).toHaveBeenCalledTimes(1);

    // 쿨다운이 끝나면 그동안 몰린 것을 한 번으로 묶어 처리한다.
    await vi.advanceTimersByTimeAsync(600);
    expect(invalidate).toHaveBeenCalledTimes(2);

    // 더 온 것이 없으면 멈춘다. 계속 도는 타이머를 남기지 않는다.
    await vi.advanceTimersByTimeAsync(2000);
    expect(invalidate).toHaveBeenCalledTimes(2);
  });

  it('사건이 끊이지 않아도 주기적으로 조회한다', async () => {
    vi.useFakeTimers();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    renderHook(() => useCalendarStream(KEY, 1), { wrapper });

    // 뒤에만 몰아 두는 단순 디바운스였다면 여기서 영영 조회하지 않는다.
    for (let round = 0; round < 4; round++) {
      handlers!.onEvent({ type: 'RESERVATION_CONFIRMED', propertyId: 1 });
      await vi.advanceTimersByTimeAsync(300);
    }
    await vi.advanceTimersByTimeAsync(600);

    expect(invalidate.mock.calls.length).toBeGreaterThan(1);
  });

  it('다른 숙소의 사건은 무시한다', async () => {
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    renderHook(() => useCalendarStream(KEY, 1), { wrapper });

    handlers!.onEvent({ type: 'RESERVATION_CONFIRMED', propertyId: 99 });

    // 서버가 조직으로 좁혀 주지만 조직 안에 숙소가 여럿일 수 있다.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(invalidate).not.toHaveBeenCalled();
  });

  it('다시 붙으면 조회해 복구한다', async () => {
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    renderHook(() => useCalendarStream(KEY, 1), { wrapper });

    handlers!.onReconnect();

    // 끊긴 동안의 변경은 사건으로 오지 않는다. 여기서 통째로 받아 온다.
    await waitFor(() => expect(invalidate).toHaveBeenCalledTimes(1));
  });

  it('숙소가 정해지기 전에는 구독하지 않는다', () => {
    renderHook(() => useCalendarStream(KEY, null), { wrapper });
    expect(handlers).toBeNull();
  });

  it('화면이 사라지면 구독을 끊는다', () => {
    const { unmount } = renderHook(() => useCalendarStream(KEY, 1), { wrapper });
    unmount();
    // 안 끊으면 연결이 쌓이고, 브라우저의 동시 연결 수는 출처당 몇 개뿐이다.
    expect(unsubscribe).toHaveBeenCalled();
  });
});
