import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchCalendar, fetchProperties } from '@/api/calendar';
import { CalendarGrid } from './CalendarGrid';
import { CONFLICT_COLOR, LEGEND, channelColor } from './channels';
import { Button } from '@/components/ui/button';
import { addDays, monthLabel, toIso } from '@/lib/dates';
import { logout } from '@/api/auth';
import { useTokenStore } from '@/auth/tokenStore';

/** 7주차는 기본 30일을 그린다. 기간 선택은 8주차다. */
const WINDOW_DAYS = 30;

export function CalendarPage() {
  const [from, setFrom] = useState(() => toIso(new Date()));
  const to = useMemo(() => addDays(from, WINDOW_DAYS - 1), [from]);

  const properties = useQuery({
    queryKey: ['properties'],
    queryFn: fetchProperties,
  });

  const propertyId = properties.data?.[0]?.id ?? null;

  const calendar = useQuery({
    queryKey: ['calendar', propertyId, from, to],
    queryFn: () => fetchCalendar(propertyId!, from, to),
    enabled: propertyId !== null,
  });

  return (
    <div className="flex h-full flex-col">
      <header className="border-b border-rule bg-paper px-5 py-3">
        <div className="flex items-center justify-between">
          <div className="flex items-baseline gap-3">
            <h1 className="text-lg font-semibold text-ink">통합 캘린더</h1>
            <span className="text-sm text-muted">
              {properties.data?.[0]?.name ?? '숙소를 불러오는 중'}
            </span>
          </div>
          <Button
            size="sm"
            onClick={async () => {
              await logout();
              useTokenStore.getState().clear();
            }}
          >
            로그아웃
          </Button>
        </div>

        <div className="mt-3 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Button size="sm" onClick={() => setFrom(addDays(from, -WINDOW_DAYS))}>
              이전
            </Button>
            <span className="min-w-32 text-center text-sm font-medium text-ink">
              {monthLabel(from)}
            </span>
            <Button size="sm" onClick={() => setFrom(addDays(from, WINDOW_DAYS))}>
              다음
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setFrom(toIso(new Date()))}>
              오늘
            </Button>
          </div>

          <div className="flex items-center gap-3 text-xs text-muted">
            {LEGEND.map((entry) => (
              <span key={entry.code} className="flex items-center gap-1">
                <i
                  className="inline-block h-2.5 w-2.5 rounded-sm"
                  style={{ background: channelColor(entry.code) }}
                />
                {entry.label}
              </span>
            ))}
            <span className="flex items-center gap-1">
              <i
                className="inline-block h-2.5 w-2.5 rounded-full"
                style={{ background: CONFLICT_COLOR }}
              />
              충돌
            </span>
          </div>
        </div>
      </header>

      <main className="min-h-0 flex-1 p-4">
        <StateSwitch
          isLoading={properties.isLoading || calendar.isLoading}
          error={properties.error ?? calendar.error}
          isEmpty={calendar.data?.units.length === 0 || properties.data?.length === 0}
          onRetry={() => {
            void properties.refetch();
            void calendar.refetch();
          }}
        >
          {calendar.data && <CalendarGrid data={calendar.data} />}
        </StateSwitch>
      </main>
    </div>
  );
}

/**
 * 로딩·빈 상태·오류 세 가지를 한곳에서 가른다.
 *
 * 와이어프레임이 세 상태를 모두 그려 두었다. 화면이 비었을 때 아무것도 없는 흰 화면을
 * 보여주면 사용자는 고장 난 것인지 아직 안 만든 것인지 알 수 없다.
 */
function StateSwitch({
  isLoading,
  error,
  isEmpty,
  onRetry,
  children,
}: {
  isLoading: boolean;
  error: unknown;
  isEmpty: boolean | undefined;
  onRetry: () => void;
  children: React.ReactNode;
}) {
  if (isLoading) {
    return (
      <div className="flex h-full items-center justify-center" data-testid="state-loading">
        <div className="animate-pulse text-sm text-muted">달력을 불러오는 중입니다…</div>
      </div>
    );
  }

  if (error) {
    return (
      <div
        className="mx-auto max-w-md rounded-lg border border-rule bg-paper p-6 text-center"
        data-testid="state-error"
      >
        <h3 className="text-base font-semibold text-ink">달력을 불러오지 못했습니다</h3>
        <p className="mt-2 text-sm text-muted">
          서버 응답이 없습니다. 잠시 뒤 다시 시도해 주세요.
        </p>
        <Button variant="primary" className="mt-4" onClick={onRetry}>
          다시 불러오기
        </Button>
      </div>
    );
  }

  if (isEmpty) {
    return (
      <div
        className="mx-auto max-w-md rounded-lg border border-rule bg-paper p-6 text-center"
        data-testid="state-empty"
      >
        <h3 className="text-base font-semibold text-ink">아직 등록한 판매 단위가 없습니다</h3>
        <p className="mt-2 text-sm text-muted">
          숙소를 만들고 판매 단위를 하나 등록하면 이 화면에 달력이 나타납니다. 판매 단위를
          만들 때 기본 요금제가 함께 생성됩니다.
        </p>
      </div>
    );
  }

  return <>{children}</>;
}
