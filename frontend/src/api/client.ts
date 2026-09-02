import { tokenStore } from '@/auth/tokenStore';

/** 서버가 내려주는 오류 본문. `shared/error/ApiError` 와 짝이다. */
export interface ApiErrorBody {
  code: string;
  message: string;
  details: string[];
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: ApiErrorBody | null,
  ) {
    super(body?.message ?? `요청이 실패했습니다. (HTTP ${status})`);
    this.name = 'ApiError';
  }

  get code(): string {
    return this.body?.code ?? 'UNKNOWN';
  }
}

/**
 * 진행 중인 갱신 요청. **동시에 여러 개가 나가지 않게 하는 장치다.**
 *
 * 리프레시 토큰은 갱신할 때마다 회전한다(ADR 0005). 액세스 토큰이 만료된 순간
 * 화면에서 요청 세 개가 동시에 나가면 셋 다 401 을 받고 셋 다 갱신을 시도하는데,
 * 첫 번째가 토큰을 교체한 뒤 두 번째가 **이미 교체된 토큰**을 들고 간다. 서버는 그걸
 * 재사용으로 판정해 그 사용자의 토큰을 전부 무효화한다. 화면에서는 멀쩡히 쓰다가
 * 갑자기 로그아웃되는 증상으로 나타나고, 원인을 찾기 어렵다.
 *
 * 그래서 갱신은 한 번만 나가고 나머지는 같은 약속을 기다린다.
 */
let refreshInFlight: Promise<string | null> | null = null;

/** 갱신이 최종 실패했을 때 앱이 로그인 화면으로 보내도록 알린다. */
type SessionExpiredHandler = () => void;
let onSessionExpired: SessionExpiredHandler = () => {};

export function setSessionExpiredHandler(handler: SessionExpiredHandler): void {
  onSessionExpired = handler;
}

/**
 * 액세스 토큰을 갱신한다.
 *
 * 쿠키가 자격증명이므로 `credentials: 'include'` 가 필요하다. 프록시로 같은 출처를
 * 만들어 두었기 때문에 `SameSite=Strict` 쿠키가 실린다.
 */
export function refreshAccessToken(): Promise<string | null> {
  if (refreshInFlight) {
    return refreshInFlight;
  }

  refreshInFlight = (async () => {
    try {
      const response = await fetch('/api/auth/refresh', {
        method: 'POST',
        credentials: 'include',
      });
      if (!response.ok) {
        tokenStore.clear();
        return null;
      }
      const body = (await response.json()) as { accessToken: string };
      tokenStore.set(body.accessToken);
      return body.accessToken;
    } catch {
      // 네트워크 오류는 토큰이 죽은 것과 다르다. 그래도 이번 요청은 실패다.
      tokenStore.clear();
      return null;
    } finally {
      // 다음 만료 때 다시 갱신할 수 있어야 하므로 반드시 비운다.
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

/** 테스트가 요청 사이의 상태를 지우기 위해 쓴다. */
export function resetRefreshState(): void {
  refreshInFlight = null;
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** 갱신 자체나 로그인처럼 401 재시도를 하면 안 되는 요청. */
  skipAuthRetry?: boolean;
}

/**
 * API 요청.
 *
 * 401 을 받으면 갱신을 **한 번** 시도하고 원래 요청을 다시 보낸다. 그것도 실패하면
 * 세션이 끝난 것으로 보고 로그인으로 보낸다. 두 번 이상 재시도하지 않는다.
 * 무한 루프가 되고, 서버 입장에서는 죽은 토큰으로 계속 두드리는 것이 된다.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const send = (token: string | null): Promise<Response> => {
    const headers: Record<string, string> = {};
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    return fetch(path, {
      method: options.method ?? 'GET',
      headers,
      credentials: 'include',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  };

  let response = await send(tokenStore.get());

  if (response.status === 401 && !options.skipAuthRetry) {
    const renewed = await refreshAccessToken();
    if (!renewed) {
      onSessionExpired();
      throw new ApiError(401, await readErrorBody(response));
    }
    response = await send(renewed);
  }

  if (!response.ok) {
    throw new ApiError(response.status, await readErrorBody(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

async function readErrorBody(response: Response): Promise<ApiErrorBody | null> {
  try {
    return (await response.json()) as ApiErrorBody;
  } catch {
    // 오류 응답이 JSON 이 아닐 수 있다. 그 자체로 실패시키지는 않는다.
    return null;
  }
}
