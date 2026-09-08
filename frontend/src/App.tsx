import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes as RouterRoutes } from 'react-router-dom';
import { LoginPage } from '@/auth/LoginPage';
import { CalendarPage } from '@/calendar/CalendarPage';
import { ChannelsPage } from '@/channels/ChannelsPage';
import { ChannelMappingPage } from '@/channels/ChannelMappingPage';
import { ConflictsPage } from '@/conflicts/ConflictsPage';
import { OpsTasksPage } from '@/ops/OpsTasksPage';
import { InboxPage } from '@/inbox/InboxPage';
import { ReportsPage } from '@/reports/ReportsPage';
import { WidgetPage } from '@/widget/WidgetPage';
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
 * 화면이 넷이 되면서 React Router 를 들였다(P3 10주차). 13주차에 충돌 화면이, 14주차에 인박스가 더해졌다.
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

  // **위젯은 토큰 검사보다 앞이다.** 계획서 8.7 의 공개 화면이라 세션이 없는 것이
  // 정상이고, 뒤에 두면 예약하러 온 손님에게 호스트용 로그인 화면이 뜬다.
  // 부팅 복구를 기다릴 이유도 없다 — 복구할 세션이 없다.
  if (isWidgetPath()) {
    return (
      <RouterRoutes>
        <Route path="/widget/:propertyId" element={<WidgetPage />} />
      </RouterRoutes>
    );
  }

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
      {/* 충돌은 채널 하나의 문제가 아니라 그 날짜 그 방의 문제다. 채널 아래 두지 않는다. */}
      <Route path="/conflicts" element={<ConflictsPage />} />

      {/* P4 15주차. 청소 태스크 칸반(계획서 8.6). */}
      <Route path="/ops/tasks" element={<OpsTasksPage />} />
      {/* 계획서 8.1 의 /inbox 와 /inbox/:threadId. 같은 화면이 목록과 대화를 함께 그린다. */}
      <Route path="/inbox" element={<InboxPage />} />
      <Route path="/inbox/:threadId" element={<InboxPage />} />
      {/* P4 16주차. 운영 리포트(계획서 8.8). 지표 여섯이 한 화면이다. */}
      <Route path="/reports" element={<ReportsPage />} />
      {/* 알 수 없는 경로는 캘린더로. 404 화면을 만들 이유가 아직 없다. */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </RouterRoutes>
  );
}

/**
 * 지금 경로가 위젯인지.
 *
 * 라우터의 `useLocation` 을 쓰지 않는 이유는 이 판정이 **라우터를 그리기 전에**
 * 필요해서다. 세션 복구와 로그인 화면이 라우터 바깥에 있고(ADR 0009), 위젯은 그
 * 둘보다도 앞이어야 한다.
 */
function isWidgetPath(): boolean {
  return window.location.pathname.startsWith('/widget/');
}
