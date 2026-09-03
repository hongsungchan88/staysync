import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { subscribeCalendar, type CalendarEvent } from './calendarStream';
import { resetRefreshState } from './client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 완료 조건 14. 실시간 구독의 연결과 복구.
 *
 * **끊겼다 붙으면 캘린더를 다시 조회한다**는 것이 여기서 지킬 계약이다. 놓친 사건을
 * 서버가 재생해 주지 않으므로, 복구를 빠뜨리면 끊긴 동안의 변경이 화면에서 영영
 * 사라진다. 그 화면은 정상으로 보인다 — 그냥 오래된 값일 뿐이다.
 *
 * 토큰을 헤더로 보내는지도 확인한다. 쿼리에 실으면 접근 토큰이 URL 로 새어 서버
 * 로그와 브라우저 이력에 남고, 리프레시를 쿠키로 감춘 결정이 무의미해진다.
 */

/** 서버가 흘려보내는 SSE 본문을 손으로 만들어 준다. */
function streamOf(chunks: string[], opts: { hold?: boolean } = {}) {
  let released!: () => void;
  const held = new Promise<void>((resolve) => {
    released = resolve;
  });

  const body = new ReadableStream<Uint8Array>({
    async start(controller) {
      const encoder = new TextEncoder();
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      if (opts.hold) {
        await held;   // 연결을 열어 둔 채로 둔다
      }
      controller.close();
    },
  });

  return { body, release: () => released() };
}

function sseResponse(body: ReadableStream<Uint8Array>, status = 200) {
  return { ok: status < 400, status, body };
}

function frame(name: string, data: unknown) {
  return `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  resetRefreshState();
  tokenStore.set('테스트-토큰');
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
  resetRefreshState();
});

/** 조건이 참이 될 때까지 기다린다. 구독은 비동기 루프라 즉시 관찰되지 않는다. */
async function until(predicate: () => boolean, timeoutMs = 1000) {
  const started = Date.now();
  while (!predicate()) {
    if (Date.now() - started > timeoutMs) {
      throw new Error('기다리던 조건이 오지 않았다');
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

describe('캘린더 실시간 구독', () => {
  it('토큰을 헤더로 보낸다. URL 에 싣지 않는다', async () => {
    const { body, release } = streamOf([], { hold: true });
    fetchMock.mockResolvedValue(sseResponse(body));

    const stop = subscribeCalendar({ onEvent: () => {}, onReconnect: () => {} });
    await until(() => fetchMock.mock.calls.length > 0);

    const [url, options] = fetchMock.mock.calls[0]!;
    expect(url).toBe('/api/calendar/stream');
    // 토큰이 URL 에 있으면 서버 로그와 브라우저 이력에 남는다.
    expect(String(url)).not.toContain('테스트-토큰');
    expect(options.headers).toMatchObject({ Authorization: 'Bearer 테스트-토큰' });

    stop();
    release();
  });

  it('사건을 받아 넘긴다', async () => {
    const received: CalendarEvent[] = [];
    const { body, release } = streamOf(
      [
        frame('connected', { orgId: 1 }),
        frame('calendar', { type: 'RESERVATION_CONFIRMED', propertyId: 7 }),
      ],
      { hold: true },
    );
    fetchMock.mockResolvedValue(sseResponse(body));

    const stop = subscribeCalendar({ onEvent: (e) => received.push(e), onReconnect: () => {} });
    await until(() => received.length > 0);

    // 연결 확인 프레임은 사건이 아니다. 이걸 사건으로 세면 접속할 때마다 조회가 는다.
    expect(received).toEqual([{ type: 'RESERVATION_CONFIRMED', propertyId: 7 }]);

    stop();
    release();
  });

  it('한 덩어리에 여러 사건이 와도 나눠 읽는다', async () => {
    const received: CalendarEvent[] = [];
    const { body, release } = streamOf(
      [
        frame('calendar', { type: 'A', propertyId: 1 }) +
          frame('calendar', { type: 'B', propertyId: 2 }),
      ],
      { hold: true },
    );
    fetchMock.mockResolvedValue(sseResponse(body));

    const stop = subscribeCalendar({ onEvent: (e) => received.push(e), onReconnect: () => {} });
    await until(() => received.length >= 2);

    expect(received.map((e) => e.type)).toEqual(['A', 'B']);
    stop();
    release();
  });

  it('덜 온 프레임은 다음 조각과 이어 읽는다', async () => {
    const received: CalendarEvent[] = [];
    // 네트워크는 프레임 경계를 지켜 주지 않는다. 중간에 잘려 오는 것이 정상이다.
    const whole = frame('calendar', { type: 'RATE_BULK_EDITED', propertyId: 3 });
    const { body, release } = streamOf(
      [whole.slice(0, 20), whole.slice(20)],
      { hold: true },
    );
    fetchMock.mockResolvedValue(sseResponse(body));

    const stop = subscribeCalendar({ onEvent: (e) => received.push(e), onReconnect: () => {} });
    await until(() => received.length > 0);

    expect(received[0]).toEqual({ type: 'RATE_BULK_EDITED', propertyId: 3 });
    stop();
    release();
  });

  // --- 완료 조건 14 --------------------------------------------------------

  it('끊겼다 붙으면 다시 조회하라고 알린다', async () => {
    let reconnects = 0;
    // 첫 연결은 곧바로 끝난다(서버가 만료시킨 상황). 두 번째는 열린 채로 둔다.
    const 첫번째 = streamOf([frame('connected', { orgId: 1 })]);
    const 두번째 = streamOf([], { hold: true });
    fetchMock
      .mockResolvedValueOnce(sseResponse(첫번째.body))
      .mockResolvedValue(sseResponse(두번째.body));

    const stop = subscribeCalendar({
      onEvent: () => {},
      onReconnect: () => {
        reconnects++;
      },
    });

    await until(() => reconnects > 0, 3000);

    // 첫 연결에서는 부르지 않아야 한다. 화면이 방금 조회했는데 또 조회하게 된다.
    // 두 번째부터가 복구다.
    expect(reconnects).toBe(1);

    stop();
    두번째.release();
  });

  it('실패해도 다시 붙는다', async () => {
    const 열린것 = streamOf([], { hold: true });
    fetchMock
      .mockRejectedValueOnce(new Error('네트워크 끊김'))
      .mockResolvedValue(sseResponse(열린것.body));

    let reconnects = 0;
    const stop = subscribeCalendar({
      onEvent: () => {},
      onReconnect: () => {
        reconnects++;
      },
    });

    // 한 번 실패했다고 포기하면 탭을 오래 둔 화면이 조용히 멈춘다.
    await until(() => fetchMock.mock.calls.length >= 2, 3000);
    expect(reconnects).toBe(0);   // 아직 한 번도 붙은 적이 없다

    stop();
    열린것.release();
  });

  it('구독을 끊으면 다시 붙지 않는다', async () => {
    const { body, release } = streamOf([], { hold: true });
    fetchMock.mockResolvedValue(sseResponse(body));

    const stop = subscribeCalendar({ onEvent: () => {}, onReconnect: () => {} });
    await until(() => fetchMock.mock.calls.length > 0);

    stop();
    release();

    const 끊은뒤 = fetchMock.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 100));
    // 화면이 사라졌는데 계속 붙으면 연결이 쌓인다.
    expect(fetchMock.mock.calls.length).toBe(끊은뒤);
  });
});
