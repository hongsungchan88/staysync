import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes as RouterRoutes } from 'react-router-dom';
import { LoginPage } from '@/auth/LoginPage';
import { CalendarPage } from '@/calendar/CalendarPage';
import { ChannelsPage } from '@/channels/ChannelsPage';
import { ChannelMappingPage } from '@/channels/ChannelMappingPage';
import { useSessionBootstrap } from '@/auth/useSessionBootstrap';
import { useTokenStore } from '@/auth/tokenStore';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 401 은 클라이언트가 갱신으로 이미 한 번 다뤘다. 여기서 또 재시도하면
      // 죽은 토큰으로 여러 번 두드리게 된다.
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes />
      </BrowserRouter>
    </QueryClientProvider>
  );
}

/**
 * 화면이 넷이 되면서 React Router 를 들였다(P3 10주차).
 *
 * 7주차에 "화면이 둘이라 라우터가 할 일이 없고, 화면이 느는 것은 P3 채널 화면"이라고
 * 적어 둔 그 시점이다. 근거는 ADR 0009 결과 절.
 *
 * **인증은 경로가 아니라 토큰 유무로 갈린다.** 토큰이 없으면 어느 경로에 있든 로그인
 * 화면을 보여 주고, 로그인하면 원래 보려던 경로가 그대로 남는다. `/login` 으로
 * 보내면 돌아올 곳을 따로 기억해야 하는데 얻는 것이 없다.
 *
 * 액세스 토큰은 메모리에만 있으므로 새로고침하면 사라진다. `useSessionBootstrap` 이
 * 갱신을 한 번 호출해 복구하고, 그동안은 로그인 화면을 보여 주지 않는다. 라우터가
 * 붙어도 이 순서는 그대로다 — 그래서 `/channels` 에서 새로고침해도 로그인으로
 * 튕기지 않는다.
 */
function Routes() {
  const bootstrapped = useSessionBootstrap();
  const accessToken = useTokenStore((s) => s.accessToken);

  if (!bootstrapped) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-muted">
        세션을 확인하는 중입니다…
      </div>
    );
  }
  if (!accessToken) {
    return <LoginPage />;
  }
  return (
    <RouterRoutes>
      <Route path="/" element={<CalendarPage />} />
      <Route path="/channels" element={<ChannelsPage />} />
      <Route path="/channels/:connectionId/mapping" element={<ChannelMappingPage />} />
      {/* 알 수 없는 경로는 캘린더로. 404 화면을 만들 이유가 아직 없다. */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </RouterRoutes>
  );
}
