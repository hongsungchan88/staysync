import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { LoginPage } from '@/auth/LoginPage';
import { CalendarPage } from '@/calendar/CalendarPage';
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
      <Routes />
    </QueryClientProvider>
  );
}

/**
 * 화면은 로그인과 캘린더 둘뿐이다.
 *
 * React Router 를 아직 끼우지 않았다. 경로가 둘이고 그중 하나는 인증 여부로만
 * 갈린다. 예약 목록과 인박스가 생기는 시점에 라우터를 넣는다.
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
  return accessToken ? <CalendarPage /> : <LoginPage />;
}
