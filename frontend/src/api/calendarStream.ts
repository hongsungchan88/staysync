import { refreshAccessToken } from './client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 캘린더 실시간 갱신 구독.
 *
 * **`EventSource` 를 쓰지 않는다.** 그것은 헤더를 붙이지 못해 접근 토큰을 쿼리에
 * 실어야 하고, 그러면 토큰이 URL 로 새어 서버 로그와 브라우저 이력에 남는다.
 * 리프레시 토큰을 `HttpOnly` 쿠키로 감춘 결정(ADR 0006)이 무의미해진다.
 * `fetch` 스트림은 헤더를 붙일 수 있으므로 토큰이 본래 자리에 그대로 있다.
 *
 * 대신 `EventSource` 가 공짜로 주던 재연결을 직접 해야 한다. 아래 `connect` 가 그것이다.
 */

/** 서버가 보내는 사건. 무엇이 바뀌었는지가 아니라 바뀌었다는 사실이다. */
export interface CalendarEvent {
  type: string;
  propertyId: number;
}

interface Options {
  /** 사건이 왔을 때. 화면은 캘린더를 다시 조회한다. */
  onEvent: (event: CalendarEvent) => void;
  /**
   * 연결이 새로 붙었을 때. **끊긴 동안의 변경을 여기서 복구한다.**
   *
   * 놓친 사건을 서버가 다시 보내 주지 않는다. 재생을 만들면 Outbox 의 최소 1회
   * 전달과 순서 보장을 화면 쪽에도 다시 구현하게 된다. 통째로 다시 조회하는 편이
   * 짧고, 무엇보다 틀릴 데가 없다.
   */
  onReconnect: () => void;
}

/** 재연결 대기. 지수적으로 늘리되 상한을 둔다. */
const FIRST_RETRY_MS = 1_000;
const MAX_RETRY_MS = 30_000;

/**
 * 구독을 시작한다. 끊기면 다시 붙는다.
 *
 * @returns 구독을 끊는 함수. 화면이 사라질 때 부른다
 */
export function subscribeCalendar({ onEvent, onReconnect }: Options): () => void {
  const controller = new AbortController();
  let retryMs = FIRST_RETRY_MS;
  let retryTimer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;
  // 첫 연결에서는 복구할 것이 없다. 화면이 방금 조회했기 때문이다.
  let everConnected = false;

  const run = async () => {
    while (!stopped) {
      try {
        const response = await fetch('/api/calendar/stream', {
          headers: authHeaders(),
          credentials: 'include',
          signal: controller.signal,
        });

        if (response.status === 401) {
          // 토큰이 만료됐다. 한 번 갱신해 보고 그래도 안 되면 재시도로 넘긴다.
          const renewed = await refreshAccessToken();
          if (!renewed) {
            throw new Error('세션이 만료되어 실시간 구독을 열 수 없습니다.');
          }
          continue;
        }
        if (!response.ok || !response.body) {
          throw new Error(`실시간 구독을 열지 못했습니다. (HTTP ${response.status})`);
        }

        if (everConnected) {
          onReconnect();
        }
        everConnected = true;
        retryMs = FIRST_RETRY_MS;

        await readStream(response.body, onEvent);
        // 스트림이 정상 종료됐다. 서버가 연결을 만료시킨 경우이며 다시 붙는다.
      } catch (error) {
        if (stopped || controller.signal.aborted) {
          return;
        }
        // 끊긴 이유는 알 필요가 없다. 어차피 다시 붙고 그때 통째로 다시 조회한다.
        console.debug('실시간 구독이 끊겼다. 다시 붙는다.', error);
      }

      if (stopped) {
        return;
      }
      await new Promise((resolve) => {
        retryTimer = setTimeout(resolve, retryMs);
      });
      retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
    }
  };

  void run();

  return () => {
    stopped = true;
    clearTimeout(retryTimer);
    controller.abort();
  };
}

function authHeaders(): Record<string, string> {
  const token = tokenStore.get();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/**
 * SSE 본문을 읽는다.
 *
 * 형식이 단순해서 라이브러리를 들이지 않았다 — 빈 줄로 끊기는 블록이고, 우리가 쓰는
 * 필드는 `event:` 와 `data:` 둘뿐이다. 여러 줄 `data` 와 주석(`:`)만 다루면 된다.
 */
async function readStream(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: CalendarEvent) => void,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      return;
    }
    buffer += decoder.decode(value, { stream: true });

    // 블록은 빈 줄로 끊긴다. 마지막 조각은 아직 덜 온 것이므로 버퍼에 남긴다.
    const blocks = buffer.split('\n\n');
    buffer = blocks.pop() ?? '';

    for (const block of blocks) {
      const parsed = parseBlock(block);
      if (parsed) {
        onEvent(parsed);
      }
    }
  }
}

function parseBlock(block: string): CalendarEvent | null {
  let name = 'message';
  const dataLines: string[] = [];

  for (const line of block.split('\n')) {
    if (line.startsWith(':')) {
      continue;   // 주석. 연결 유지용으로 오는 경우가 있다
    }
    if (line.startsWith('event:')) {
      name = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trim());
    }
  }

  // 연결 확인 프레임은 사건이 아니다. 화면이 이걸 받고 다시 조회하면 접속할 때마다
  // 조회가 두 번 나간다.
  if (name !== 'calendar' || dataLines.length === 0) {
    return null;
  }

  try {
    const data = JSON.parse(dataLines.join('\n')) as CalendarEvent;
    return typeof data.propertyId === 'number' ? data : null;
  } catch {
    // 깨진 프레임 하나 때문에 구독을 끊지 않는다.
    return null;
  }
}
