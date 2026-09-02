import { useEffect } from 'react';
import { refreshAccessToken, setSessionExpiredHandler } from '@/api/client';
import { useTokenStore } from './tokenStore';

/**
 * 앱이 뜰 때 세션을 복구한다.
 *
 * 액세스 토큰을 메모리에만 두므로 새로고침하면 사라진다. 리프레시 쿠키는 브라우저가
 * 들고 있으니 갱신을 한 번 호출해 새 액세스 토큰을 받아온다. 이 호출이 끝나기 전에
 * 로그인 화면을 보여주면, 로그인된 사용자가 새로고침할 때마다 로그인 화면이 깜빡인다.
 * 그래서 `bootstrapped` 가 될 때까지 화면을 잡아 둔다.
 */
export function useSessionBootstrap(): boolean {
  const bootstrapped = useTokenStore((s) => s.bootstrapped);
  const markBootstrapped = useTokenStore((s) => s.markBootstrapped);

  useEffect(() => {
    setSessionExpiredHandler(() => useTokenStore.getState().clear());

    let cancelled = false;
    void refreshAccessToken().finally(() => {
      // 실패해도 부팅은 끝난 것이다. 토큰이 없으면 로그인 화면으로 간다.
      if (!cancelled) {
        markBootstrapped();
      }
    });
    return () => {
      cancelled = true;
    };
  }, [markBootstrapped]);

  return bootstrapped;
}
