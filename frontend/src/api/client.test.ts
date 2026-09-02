import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiRequest, refreshAccessToken, resetRefreshState } from './client';
import { tokenStore } from '@/auth/tokenStore';

/**
 * 완료 조건 10. 갱신 요청이 동시에 여러 개 나가지 않는지.
 *
 * 리프레시 토큰은 갱신할 때마다 회전한다(ADR 0005). 액세스 토큰이 만료된 순간 화면에서
 * 요청 여럿이 동시에 나가면 전부 401 을 받고 전부 갱신을 시도하는데, 두 번째부터는
 * **이미 교체된 토큰**을 들고 간다. 서버는 그걸 재사용으로 판정해 그 사용자의 토큰을
 * 전부 무효화한다. 멀쩡히 쓰다가 갑자기 로그아웃되는 증상이고, 재현이 어렵다.
 *
 * 그래서 "갱신은 한 번만"이 이 계층의 핵심 계약이다.
 */
describe('API 클라이언트의 토큰 갱신', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    resetRefreshState();
    tokenStore.set('만료된-토큰');
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    resetRefreshState();
    tokenStore.clear();
  });

  function jsonResponse(status: number, body: unknown): Response {
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
    } as Response;
  }

  it('401 을 받은 요청이 여럿이어도 갱신은 한 번만 나간다', async () => {
    let refreshCalls = 0;

    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      if (url === '/api/auth/refresh') {
        refreshCalls++;
        // 갱신이 즉시 끝나면 경쟁이 재현되지 않는다. 한 틱 늦춘다.
        await new Promise((resolve) => setTimeout(resolve, 10));
        return jsonResponse(200, { accessToken: '새-토큰', tokenType: 'Bearer', expiresInSeconds: 1800 });
      }
      const auth = (init?.headers as Record<string, string> | undefined)?.['Authorization'];
      // 새 토큰이면 성공, 옛 토큰이면 401
      return auth === 'Bearer 새-토큰'
        ? jsonResponse(200, { ok: true })
        : jsonResponse(401, { code: 'UNAUTHORIZED', message: '만료', details: [] });
    });

    // 만료된 토큰으로 세 요청이 동시에 나간다
    const results = await Promise.all([
      apiRequest('/api/properties'),
      apiRequest('/api/reservations'),
      apiRequest('/api/properties/1/calendar'),
    ]);

    expect(results).toHaveLength(3);
    expect(refreshCalls).toBe(1);
    expect(tokenStore.get()).toBe('새-토큰');
  });

  it('갱신을 동시에 여러 번 불러도 요청은 한 번이다', async () => {
    let refreshCalls = 0;
    fetchMock.mockImplementation(async () => {
      refreshCalls++;
      await new Promise((resolve) => setTimeout(resolve, 10));
      return jsonResponse(200, { accessToken: '새-토큰', tokenType: 'Bearer', expiresInSeconds: 1800 });
    });

    const tokens = await Promise.all([
      refreshAccessToken(),
      refreshAccessToken(),
      refreshAccessToken(),
    ]);

    expect(refreshCalls).toBe(1);
    expect(tokens).toEqual(['새-토큰', '새-토큰', '새-토큰']);
  });

  it('갱신이 끝나면 다음 만료 때 다시 갱신할 수 있다', async () => {
    let refreshCalls = 0;
    fetchMock.mockImplementation(async () => {
      refreshCalls++;
      return jsonResponse(200, { accessToken: `토큰-${refreshCalls}`, tokenType: 'Bearer', expiresInSeconds: 1800 });
    });

    await refreshAccessToken();
    await refreshAccessToken();

    // 진행 중인 약속을 비우지 않으면 두 번째 만료에서 갱신이 영영 안 나간다.
    expect(refreshCalls).toBe(2);
  });

  it('갱신이 실패하면 토큰을 비우고 한 번만 시도한다', async () => {
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (url: string) => {
      if (url === '/api/auth/refresh') {
        refreshCalls++;
        return jsonResponse(401, { code: 'REFRESH_FAILED', message: '다시 로그인해 주세요.', details: [] });
      }
      return jsonResponse(401, { code: 'UNAUTHORIZED', message: '만료', details: [] });
    });

    await expect(apiRequest('/api/properties')).rejects.toThrow();

    // 갱신 실패 후 원래 요청을 다시 보내지 않는다. 무한 루프가 된다.
    expect(refreshCalls).toBe(1);
    expect(tokenStore.get()).toBeNull();
  });

  it('로그인 요청의 401 은 갱신을 시도하지 않는다', async () => {
    let refreshCalls = 0;
    fetchMock.mockImplementation(async (url: string) => {
      if (url === '/api/auth/refresh') {
        refreshCalls++;
        return jsonResponse(200, { accessToken: 'x', tokenType: 'Bearer', expiresInSeconds: 1 });
      }
      return jsonResponse(401, { code: 'LOGIN_FAILED', message: '틀림', details: [] });
    });

    await expect(
      apiRequest('/api/auth/login', { method: 'POST', body: {}, skipAuthRetry: true }),
    ).rejects.toThrow();

    // 비밀번호가 틀린 것은 만료가 아니다. 갱신할 이유가 없다.
    expect(refreshCalls).toBe(0);
  });
});
