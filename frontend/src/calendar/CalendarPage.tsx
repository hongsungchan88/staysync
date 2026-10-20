import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchCalendar, fetchProperties } from '@/api/calendar';
import { fetchChannels } from '@/api/channels';
import { CalendarGrid } from './CalendarGrid';
import { SelectionPanel } from './SelectionPanel';
import { useReservationMove, type MoveRequest } from './useReservationMove';
import { useCalendarStream } from './useCalendarStream';
import type { SelectionRect } from './selection';
import type { ReservationBar } from '@/api/schemas';
import { CONFLICT_COLOR, LEGEND, channelColor } from './channels';
import { Button } from '@/components/ui/button';
import { addDays, monthLabel, toIso } from '@/lib/dates';
import { cn } from '@/lib/utils';
import { useNarrow } from '@/lib/useNarrow';
import { logout } from '@/api/auth';
import { useTokenStore } from '@/auth/tokenStore';

/**
 * 한 화면에 그리는 기간. 7주차와 같은 30일이다.
 *
 * 가상 스크롤이 붙어 90일도 감당하지만 기본값은 바꾸지 않았다. 기본 기간을 정하는 것은
 * 작업지시 05 에 없는 판단이라 임의로 정하지 않는다. 90일로 재려면 측정할 때만 바꾼다
 * (`docs/측정-02-*.md` 의 재현 방법).
 */
const WINDOW_DAYS = 30;

/** 주 메뉴. 링크 그대로이고 지금 화면은 NavLink 가 `aria-current="page"` 를 단다(작업지시-29). */
const MAIN_MENU = [
  { to: '/', label: '캘린더' },
  { to: '/properties', label: '숙소' },
  { to: '/channels', label: '채널 연결' },
  { to: '/conflicts', label: '충돌' },
  { to: '/inbox', label: '인박스' },
  { to: '/ops/tasks', label: '청소' },
  { to: '/reports', label: '리포트' },
] as const;

const menuClass = ({ isActive }: { isActive: boolean }) =>
  cn(
    'inline-flex h-8 shrink-0 items-center whitespace-nowrap rounded-md border px-3 text-xs hover:bg-faint',
    isActive ? 'border-clay font-semibold text-clay' : 'border-rule-strong text-body',
  );

const accountClass = ({ isActive }: { isActive: boolean }) =>
  cn(
    'inline-flex h-8 shrink-0 items-center whitespace-nowrap rounded-md px-2 text-xs hover:bg-faint',
    isActive ? 'font-semibold text-clay' : 'text-muted',
  );

export function CalendarPage() {
  const [from, setFrom] = useState(() => toIso(new Date()));
  const to = useMemo(() => addDays(from, WINDOW_DAYS - 1), [from]);
  const [selection, setSelection] = useState<SelectionRect | null>(null);
  // 누른 예약 막대. 새로 범위를 끌면 닫힌다 — 패널 자리가 하나라 나중 조작이 이긴다.
  const [bar, setBar] = useState<ReservationBar | null>(null);
  const onSelect = useCallback((rect: SelectionRect | null) => {
    setSelection(rect);
    if (rect !== null) {
      setBar(null);
    }
  }, []);
  const [rejection, setRejection] = useState<string | null>(null);

  // 좁은 화면의 아래 판은 Esc 와 닫기로 접는다(작업지시-29). 넓은 화면의 동작은 바꾸지 않는다.
  const narrow = useNarrow();
  const closePanel = useCallback(() => {
    setSelection(null);
    setBar(null);
  }, []);
  useEffect(() => {
    if (!narrow) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closePanel();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [narrow, closePanel]);

  const properties = useQuery({
    queryKey: ['properties'],
    queryFn: fetchProperties,
  });

  // 고른 숙소. 처음엔 첫 숙소다. P5 17주차까지 첫 숙소만 그렸는데, 업체 숙소가 둘이
  // 들어오자 두 번째가 화면에서 닿지 않았다(확인-09 7절). 좌표 계산·드래그 선택은 숙소와
  // 무관하므로 바꾸는 것은 어느 숙소의 캘린더를 부르는지뿐이다.
  const [chosenPropertyId, setChosenPropertyId] = useState<number | null>(null);
  const propertyId = chosenPropertyId ?? properties.data?.[0]?.id ?? null;
  const property = properties.data?.find((p) => p.id === propertyId);

  const calendarKey = useMemo(
    () => ['calendar', propertyId, from, to] as const,
    [propertyId, from, to],
  );

  const calendar = useQuery({
    queryKey: calendarKey,
    queryFn: () => fetchCalendar(propertyId!, from, to),
    enabled: propertyId !== null,
  });

  // 범례에는 연결이 있거나 화면에 막대가 있는 채널만 둔다. 연동이 없는 채널이 범례에
  // 서 있으면 "연결했는데 예약이 안 온다"로 읽힌다(작업지시-20 D). 직접예약은 늘 있다.
  const channels = useQuery({ queryKey: ['channels'], queryFn: fetchChannels });
  const legend = useMemo(() => {
    const live = new Set<string>(['DIRECT']);
    channels.data?.forEach((c) => live.add(c.channelCode));
    calendar.data?.reservations.forEach((r) => live.add(r.channel));
    // 에어비앤비는 iCal·Channex 두 코드가 한 항목이다(작업지시-21 C).
    return LEGEND.filter((entry) => entry.codes.some((code) => live.has(code)));
  }, [channels.data, calendar.data]);

  // 다른 채널의 예약이나 다른 탭의 편집이 이 화면에 즉시 들어온다(계획서 8.2).
  useCalendarStream(calendarKey, propertyId);

  const { move } = useReservationMove(calendarKey, setRejection);
  const onMove = useCallback(
    (request: MoveRequest) => {
      // 다시 시도할 때 앞의 거절 문구가 남아 있으면 어느 시도의 결과인지 알 수 없다.
      setRejection(null);
      move(request);
    },
    [move],
  );

  return (
    <div className="flex h-full flex-col">
      <header className="border-b border-rule bg-paper px-3 py-2 md:px-5 md:py-3">
        {/*
          넓은 화면은 한 줄(왼쪽 제목·숙소, 오른쪽 메뉴·계정), 좁으면 메뉴 묶음이 다음 줄로 내려가고 메뉴는 가로로 넘긴다
          (작업지시-29). 줄바꿈은 띄어쓰기에서만 — index.css 의 keep-all.
        */}
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
          <div className="flex min-w-0 items-baseline gap-3">
            <h1 className="shrink-0 whitespace-nowrap text-lg font-semibold text-ink">통합 캘린더</h1>
            {properties.data && properties.data.length > 1 ? (
              <select
                aria-label="숙소"
                data-testid="property-select"
                className="h-8 min-w-0 max-w-[14rem] truncate rounded-md border border-rule-strong bg-paper px-2 text-sm text-ink"
                value={propertyId ?? ''}
                onChange={(event) => {
                  setChosenPropertyId(Number(event.target.value));
                  // 다른 숙소의 선택 범위와 예약 패널은 의미가 없다.
                  setSelection(null);
                  setBar(null);
                }}
              >
                {properties.data.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            ) : (
              <span className="truncate whitespace-nowrap text-sm text-muted">{property?.name ?? '숙소를 불러오는 중'}</span>
            )}
          </div>
          <div className="flex min-w-0 max-w-full items-center gap-2">
            <nav aria-label="주 메뉴" className="flex min-w-0 items-center gap-2 overflow-x-auto" data-testid="main-nav">
              {MAIN_MENU.map((item) => (
                <NavLink key={item.to} to={item.to} end className={menuClass}>
                  {item.label}
                </NavLink>
              ))}
            </nav>
            {/* 계정은 주 메뉴와 떨어진 오른쪽 끝, 테두리 없는 모양으로 구분한다. */}
            <div role="group" aria-label="계정" className="flex shrink-0 items-center gap-1 border-l border-rule pl-2">
              <NavLink to="/account/password" end className={accountClass}>
                비밀번호 변경
              </NavLink>
              <Button
                size="sm"
                variant="ghost"
                className="whitespace-nowrap text-muted"
                onClick={async () => {
                  await logout();
                  useTokenStore.getState().clear();
                }}
              >
                로그아웃
              </Button>
            </div>
          </div>
        </div>

        <div className="mt-2 flex flex-wrap items-center justify-between gap-2 md:mt-3">
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

          <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted">
            {legend.map((entry) => (
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

      {/*
        옮기기가 거절된 이유. 낙관적 업데이트는 실패해도 화면이 잠깐 정상으로 보이므로,
        되돌리는 것만으로는 사용자가 무슨 일이 있었는지 알 수 없다.
      */}
      {rejection && (
        <div
          role="alert"
          data-testid="move-rejected"
          className="flex items-center justify-between border-b border-warn/40 bg-warn/10 px-5 py-2 text-sm text-warn"
        >
          <span>예약을 옮기지 못했습니다. {rejection}</span>
          <Button size="sm" variant="ghost" onClick={() => setRejection(null)}>
            닫기
          </Button>
        </div>
      )}

      {/* 좁은 화면은 표가 전체 폭이고 패널은 표 아래 판이 된다(작업지시-29). 넓은 화면은 그대로 오른쪽. */}
      <div className="flex min-h-0 flex-1 max-md:flex-col">
        {/*
          `min-w-0` 이 없으면 가로 가상 스크롤이 통째로 죽는다. flex 항목의 기본
          `min-width` 는 `auto` 라 안쪽 내용(90일 × 66px = 5,940px)만큼 늘어나고, 그러면
          스크롤 컨테이너의 `offsetWidth` 가 곧 전체 너비가 돼 "보이는 구간"이 전부가 된다.
          세로가 멀쩡했던 것은 `min-h-0` 이 이미 있었기 때문이다. 재측정에서 잡혔다.
        */}
        <main className="min-h-0 min-w-0 flex-1 p-1 md:p-4">
          <StateSwitch
            isLoading={properties.isLoading || calendar.isLoading}
            error={properties.error ?? calendar.error}
            isEmpty={calendar.data?.units.length === 0 || properties.data?.length === 0}
            onRetry={() => {
              void properties.refetch();
              void calendar.refetch();
            }}
          >
            {calendar.data && (
              <CalendarGrid
                data={calendar.data}
                onSelect={onSelect}
                onMove={onMove}
                onBarClick={setBar}
              />
            )}
          </StateSwitch>
        </main>

        {calendar.data && propertyId !== null && (
          <div
            className="flex min-h-0 max-md:max-h-[55vh] max-md:flex-col max-md:overflow-y-auto max-md:border-t max-md:border-rule max-md:[&>aside]:w-full max-md:[&>aside]:border-l-0"
            data-testid="panel-dock"
          >
            {/* 기간 편집 패널에는 닫기가 없다(넓은 화면은 다시 끌면 바뀐다). 좁은 화면의 판에는 닫기를 단다. */}
            {narrow && selection !== null && bar === null && (
              <div className="flex justify-end border-b border-rule bg-paper px-2 py-1">
                <Button size="sm" variant="ghost" onClick={closePanel} data-testid="panel-close">
                  닫기
                </Button>
              </div>
            )}
            <SelectionPanel
              data={calendar.data}
              rect={selection}
              bar={bar}
              onCloseBar={() => setBar(null)}
              propertyId={propertyId}
              calendarKey={calendarKey}
            />
          </div>
        )}
      </div>
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
        <Link
          to="/properties"
          className="mt-4 inline-flex h-8 items-center rounded-md border border-rule-strong px-3 text-xs text-body hover:bg-faint"
        >
          숙소 등록하러 가기
        </Link>
      </div>
    );
  }

  return <>{children}</>;
}
