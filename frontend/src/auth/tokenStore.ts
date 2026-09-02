import { create } from 'zustand';

/**
 * 액세스 토큰 보관소. **메모리에만 둔다.**
 *
 * `localStorage` 에 넣지 않는 이유가 이 파일의 요점이다. XSS 하나로 30분짜리 토큰이
 * 통째로 나가는데, 리프레시 토큰을 `HttpOnly` 쿠키로 감춘 결정(ADR 0006)이 그러면
 * 의미가 없어진다. 공격자가 리프레시를 못 읽어도 액세스 토큰만으로 30분을 쓴다.
 *
 * 대가는 새로고침하면 토큰이 사라진다는 것이다. 그래서 앱이 뜰 때 갱신을 한 번
 * 호출해 복구한다(`useSessionBootstrap`). 쿠키는 브라우저가 들고 있으므로 그것으로
 * 새 액세스 토큰을 받아온다.
 */
interface TokenState {
  accessToken: string | null;
  /** 앱 기동 시 갱신을 시도했는지. 끝나기 전에는 로그인 화면으로 보내지 않는다. */
  bootstrapped: boolean;
  setAccessToken: (token: string | null) => void;
  markBootstrapped: () => void;
  clear: () => void;
}

export const useTokenStore = create<TokenState>((set) => ({
  accessToken: null,
  bootstrapped: false,
  setAccessToken: (token) => set({ accessToken: token }),
  markBootstrapped: () => set({ bootstrapped: true }),
  clear: () => set({ accessToken: null }),
}));

/**
 * 스토어 밖에서 토큰을 읽고 쓴다.
 *
 * API 클라이언트는 React 훅 규칙에 묶이지 않아야 한다. 요청 인터셉터가 컴포넌트가
 * 아니기 때문이다.
 */
export const tokenStore = {
  get: () => useTokenStore.getState().accessToken,
  set: (token: string | null) => useTokenStore.getState().setAccessToken(token),
  clear: () => useTokenStore.getState().clear(),
};
