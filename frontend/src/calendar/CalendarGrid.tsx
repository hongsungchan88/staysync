import { useMemo, useRef, useState, type Key } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import type { CalendarGrid as GridData, ReservationBar } from '@/api/schemas';
import { channelColor, channelLabel } from './channels';
import {
  DAY_W,
  HEAD_H,
  LANE_H,
  LEFT_W,
  TEXT_H,
  layoutGrid,
  type PlacedBar,
  type UnitLayout,
} from './layout';
import { dayOfMonth, isWeekend, weekdayLabel } from '@/lib/dates';
import { cn } from '@/lib/utils';

/**
 * 캘린더 그리드.
 *
 * **세로·가로 양방향 가상 스크롤이다.** 30객실 × 90일이면 셀이 2,700개이고 7주차 측정에서
 * DOM 노드가 11,229개까지 갔다(`docs/측정-01-캘린더-초기렌더링.md`). 세로만 자르면 90일이라는
 * 가로 병목이 그대로 남아 노드 수가 크게 줄지 않는다.
 *
 * 보이지 않는 부분은 **여백 한 칸**으로 대신한다. 항목을 절대 위치로 흩뿌리는 방법도 있지만
 * 그러면 좌측 목록과 날짜 헤더의 `position: sticky` 가 죽는다. 절대 위치 요소는 고정되지
 * 않기 때문이다. 여백으로 밀면 문서 흐름이 유지돼 두 축의 고정이 그대로 산다.
 *
 * 행 높이는 판매 단위마다 다르다. 그날 겹치는 예약 수만큼 줄이 생기기 때문이다(`layout.ts`).
 */
interface Props {
  data: GridData;
}

/** 화면 밖으로 미리 그려 두는 여유분. 스크롤할 때 빈 칸이 스치는 것을 막는다. */
const OVERSCAN = 4;

export function CalendarGrid({ data }: Props) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const dates = useMemo(() => data.units[0]?.days.map((day) => day.date) ?? [], [data]);
  const layouts = useMemo(() => layoutGrid(data), [data]);

  const rowVirtualizer = useVirtualizer({
    count: data.units.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: (index) => layouts.get(data.units[index]!.id)?.height ?? 0,
    overscan: OVERSCAN,
    // 헤더가 스크롤 컨테이너 안에 있으므로 첫 행은 그만큼 아래에서 시작한다.
    paddingStart: HEAD_H,
  });

  const colVirtualizer = useVirtualizer({
    horizontal: true,
    count: dates.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => DAY_W,
    overscan: OVERSCAN,
    paddingStart: LEFT_W,
  });

  const rows = rowVirtualizer.getVirtualItems();
  const cols = colVirtualizer.getVirtualItems();

  if (data.units.length === 0 || dates.length === 0) {
    return null;
  }

  // 보이는 구간의 앞뒤를 여백으로 메운다. 여백이 있어야 스크롤 막대의 길이와 위치가
  // 전체 데이터 기준으로 맞는다.
  const padLeft = (cols[0]?.start ?? LEFT_W) - LEFT_W;
  const padRight = colVirtualizer.getTotalSize() - (cols[cols.length - 1]?.end ?? LEFT_W);
  const padTop = (rows[0]?.start ?? HEAD_H) - HEAD_H;
  const padBottom = rowVirtualizer.getTotalSize() - (rows[rows.length - 1]?.end ?? HEAD_H);

  return (
    <div ref={scrollRef} className="h-full overflow-auto bg-paper" data-testid="calendar-grid">
      <div style={{ width: colVirtualizer.getTotalSize() }}>
        {/* 날짜 헤더. 세로 스크롤에 고정된다. */}
        <div
          className="sticky top-0 z-20 flex bg-paper"
          style={{ height: HEAD_H }}
          data-testid="date-header"
        >
          {/* 좌상단 모서리. 두 축 모두에 고정되므로 z 가 가장 높다. */}
          <div
            className="sticky left-0 z-30 flex shrink-0 items-center border-r border-b border-rule-strong bg-paper px-3 text-xs font-medium text-muted"
            style={{ width: LEFT_W }}
          >
            판매 단위 {data.units.length}개
          </div>
          <div style={{ width: padLeft }} />
          {cols.map((col) => {
            const date = dates[col.index]!;
            return (
              <div
                key={col.key}
                data-testid={`day-head-${date}`}
                className={cn(
                  'flex shrink-0 flex-col items-center justify-center border-b border-rule text-center',
                  isWeekend(date) ? 'bg-sand' : 'bg-paper',
                )}
                style={{ width: DAY_W }}
              >
                <span className="text-[11px] text-muted">{weekdayLabel(date)}</span>
                <span className="text-sm font-medium text-ink">{dayOfMonth(date)}</span>
              </div>
            );
          })}
          <div style={{ width: padRight }} />
        </div>

        <div style={{ height: padTop }} />
        {rows.map((row) => {
          const unit = data.units[row.index]!;
          return (
            <UnitRow
              key={row.key}
              unit={unit}
              layout={layouts.get(unit.id)!}
              cols={cols}
              padLeft={padLeft}
              padRight={padRight}
              height={row.size}
            />
          );
        })}
        <div style={{ height: padBottom }} />
      </div>
    </div>
  );
}

interface VisibleColumn {
  index: number;
  key: Key;
}

function UnitRow({
  unit,
  layout,
  cols,
  padLeft,
  padRight,
  height,
}: {
  unit: GridData['units'][number];
  layout: UnitLayout;
  cols: VisibleColumn[];
  padLeft: number;
  padRight: number;
  height: number;
}) {
  const firstVisible = cols[0]?.index ?? 0;
  const lastVisible = cols[cols.length - 1]?.index ?? 0;

  // 보이는 구간에 걸친 막대만 그린다. 가로 가상 스크롤의 나머지 절반이 이것이다 —
  // 셀만 줄이고 막대를 전부 그리면 90일치 막대가 그대로 남는다.
  const visibleBars = layout.bars.filter(
    (placed) => placed.endIndex > firstVisible && placed.startIndex <= lastVisible,
  );

  return (
    <div className="relative flex" style={{ height }} data-testid={`row-${unit.id}`}>
      <div
        className="sticky left-0 z-10 flex shrink-0 flex-col justify-center border-r border-b border-rule-strong bg-paper px-3"
        style={{ width: LEFT_W }}
      >
        <span className="truncate text-sm text-ink">{unit.name}</span>
        <span className="text-[11px] text-muted">총 {unit.totalUnits}실</span>
      </div>

      <div style={{ width: padLeft }} />
      {cols.map((col) => (
        <DayCell
          key={col.key}
          unitId={unit.id}
          day={unit.days[col.index]!}
          folded={layout.foldedByDay.get(col.index) ?? []}
          laneCount={layout.laneCount}
        />
      ))}
      <div style={{ width: padRight }} />

      {/*
        막대는 셀 위에 절대 위치로 얹는다. 여러 칸에 걸치므로 셀 안에 넣을 수 없다.
        기준점이 행의 왼쪽 끝이라 좌측 목록 너비를 더해야 날짜 칸과 맞는다.
      */}
      {visibleBars.map((placed) => (
        <Bar key={placed.bar.id} placed={placed} />
      ))}
    </div>
  );
}

function DayCell({
  unitId,
  day,
  folded,
  laneCount,
}: {
  unitId: number;
  day: GridData['units'][number]['days'][number];
  folded: ReservationBar[];
  laneCount: number;
}) {
  const [open, setOpen] = useState(false);

  return (
    <div
      data-testid={`cell-${unitId}-${day.date}`}
      className={cn(
        'relative flex shrink-0 flex-col border-b border-r border-rule px-1.5',
        isWeekend(day.date) ? 'bg-sand/40' : 'bg-paper',
        day.stopSell && 'bg-rule/60',
      )}
      style={{ width: DAY_W }}
    >
      <div className="flex flex-col justify-center gap-0.5" style={{ height: TEXT_H }}>
        <span
          className={cn(
            'text-[11px] tabular-nums',
            day.avail <= 0 ? 'font-semibold text-warn' : 'text-body',
          )}
        >
          {day.avail <= 0 ? '마감' : `${day.avail}실`}
        </span>
        <span className="truncate text-[11px] tabular-nums text-muted">
          {day.price.toLocaleString('ko-KR')}
        </span>
        <span className="flex gap-1 text-[10px] leading-none text-muted">
          {day.minStay > 1 && <span title="최소 숙박">{day.minStay}박~</span>}
          {day.stopSell && <span title="판매중지">중지</span>}
          {/* conflict 는 P3 에서 실제로 채워진다. 화면은 지금부터 그린다. */}
          {day.conflict && (
            <span className="text-warn" title="중복예약 충돌">
              ●
            </span>
          )}
        </span>
      </div>

      {/* 상한을 넘어 접힌 예약. 마지막 줄 자리에 놓아 막대와 겹치지 않는다. */}
      {folded.length > 0 && (
        <button
          type="button"
          data-testid={`folded-${unitId}-${day.date}`}
          onClick={() => setOpen((v) => !v)}
          className="absolute left-1 z-10 rounded bg-ink/80 px-1 text-[10px] leading-4 text-paper"
          style={{ top: TEXT_H + (laneCount - 1) * LANE_H + 2 }}
        >
          +{folded.length}
        </button>
      )}
      {open && (
        <div
          className="absolute left-1 top-full z-40 w-44 rounded border border-rule bg-paper p-2 shadow-lg"
          data-testid={`folded-list-${unitId}-${day.date}`}
        >
          <p className="mb-1 text-[11px] font-medium text-ink">{day.date} 접힌 예약</p>
          <ul className="space-y-1">
            {folded.map((bar) => (
              <li key={bar.id} className="truncate text-[11px] text-body">
                {bar.guestName ?? '이름 없음'} · {channelLabel(bar.channel)}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

/**
 * 예약 막대.
 *
 * 조회 기간을 넘어서는 예약을 잘라 내는 계산은 `layout.ts` 가 이미 했다. 여기서는 칸
 * 인덱스를 픽셀로 바꾸기만 한다.
 */
function Bar({ placed }: { placed: PlacedBar }) {
  const { bar, lane, startIndex, endIndex } = placed;
  const isHold = bar.status === 'HOLD';

  return (
    <div
      data-testid={`bar-${bar.id}`}
      title={`${bar.guestName ?? '이름 없음'} · ${channelLabel(bar.channel)} · ${bar.status}`}
      className="absolute flex items-center overflow-hidden rounded px-2 text-[11px] text-paper"
      style={{
        left: LEFT_W + startIndex * DAY_W + 3,
        width: (endIndex - startIndex) * DAY_W - 6,
        top: TEXT_H + lane * LANE_H + 2,
        height: LANE_H - 4,
        background: channelColor(bar.channel),
        // HOLD 는 아직 확정되지 않은 점유라 빗금으로 구분한다. 와이어프레임과 같다.
        backgroundImage: isHold
          ? 'repeating-linear-gradient(45deg, rgba(255,255,255,.25), rgba(255,255,255,.25) 4px, transparent 4px, transparent 8px)'
          : undefined,
        opacity: bar.status === 'CHECKED_OUT' ? 0.65 : 1,
      }}
    >
      <span className="truncate">{bar.guestName ?? '이름 없음'}</span>
    </div>
  );
}
