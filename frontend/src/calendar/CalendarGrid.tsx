import { useMemo } from 'react';
import type { CalendarGrid as GridData, ReservationBar } from '@/api/schemas';
import { channelColor, channelLabel } from './channels';
import { daysBetween, dayOfMonth, isWeekend, weekdayLabel } from '@/lib/dates';
import { cn } from '@/lib/utils';

/**
 * 캘린더 그리드.
 *
 * 좌측 판매 단위 열과 상단 날짜 헤더를 `position: sticky` 로 고정한다. 세로·가로
 * 스크롤에도 붙어 있어야 어느 방/어느 날짜인지 알 수 있다.
 *
 * **가상 스크롤은 8주차다.** 30일 × 판매 단위 몇 개는 전부 그려도 문제가 없고,
 * 처음부터 넣으면 무엇이 느린지 알기 전에 최적화하는 셈이 된다. 실제 측정값을 보고
 * 8주차 범위를 정한다.
 *
 * **드래그 이동도 8주차다.** 여기서는 막대를 그리기만 한다.
 */
interface Props {
  data: GridData;
}

export function CalendarGrid({ data }: Props) {
  const dates = data.units[0]?.days.map((day) => day.date) ?? [];

  // 판매 단위별 막대를 미리 묶어 둔다. 행마다 전체 목록을 훑으면 막대가 늘수록
  // 비용이 제곱으로 는다.
  const barsByUnit = useMemo(() => {
    const grouped = new Map<number, ReservationBar[]>();
    for (const bar of data.reservations) {
      const list = grouped.get(bar.unitId);
      if (list) {
        list.push(bar);
      } else {
        grouped.set(bar.unitId, [bar]);
      }
    }
    return grouped;
  }, [data.reservations]);

  if (data.units.length === 0) {
    return null;
  }

  return (
    <div className="overflow-auto bg-paper" data-testid="calendar-grid">
      <div
        className="relative grid"
        style={{
          gridTemplateColumns: `var(--left-w) repeat(${dates.length}, var(--day-w))`,
        }}
      >
        {/* 좌상단 모서리. 가로·세로 양쪽으로 고정된다. */}
        <div
          className="sticky top-0 left-0 z-30 flex items-center border-r border-b border-rule-strong bg-paper px-3 text-xs font-medium text-muted"
          style={{ height: 'var(--head-h)' }}
        >
          판매 단위 {data.units.length}개
        </div>

        {dates.map((date) => (
          <div
            key={date}
            className={cn(
              'sticky top-0 z-20 flex flex-col items-center justify-center border-b border-rule text-center',
              isWeekend(date) ? 'bg-sand' : 'bg-paper',
            )}
            style={{ height: 'var(--head-h)' }}
          >
            <span className="text-[11px] text-muted">{weekdayLabel(date)}</span>
            <span className="text-sm font-medium text-ink">{dayOfMonth(date)}</span>
          </div>
        ))}

        {data.units.map((unit) => (
          <UnitLine
            key={unit.id}
            unit={unit}
            dates={dates}
            bars={barsByUnit.get(unit.id) ?? []}
            gridFrom={data.from}
          />
        ))}
      </div>
    </div>
  );
}

function UnitLine({
  unit,
  dates,
  bars,
  gridFrom,
}: {
  unit: GridData['units'][number];
  dates: string[];
  bars: ReservationBar[];
  gridFrom: string;
}) {
  return (
    <>
      <div
        className="sticky left-0 z-10 flex flex-col justify-center border-r border-b border-rule-strong bg-paper px-3"
        style={{ height: 'var(--row-h)' }}
      >
        <span className="truncate text-sm text-ink">{unit.name}</span>
        <span className="text-[11px] text-muted">총 {unit.totalUnits}실</span>
      </div>

      <div
        className="relative col-span-full col-start-2 grid"
        style={{
          gridTemplateColumns: `repeat(${dates.length}, var(--day-w))`,
          height: 'var(--row-h)',
        }}
      >
        {unit.days.map((day) => (
          <div
            key={day.date}
            data-testid={`cell-${unit.id}-${day.date}`}
            className={cn(
              'flex flex-col justify-center gap-0.5 border-b border-r border-rule px-1.5',
              isWeekend(day.date) ? 'bg-sand/40' : 'bg-paper',
              day.stopSell && 'bg-rule/60',
            )}
          >
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
        ))}

        {bars.map((bar) => (
          <Bar key={bar.id} bar={bar} gridFrom={gridFrom} totalDays={dates.length} />
        ))}
      </div>
    </>
  );
}

/**
 * 예약 막대.
 *
 * 조회 기간을 넘어서는 예약은 화면 밖으로 잘라 그린다. 백엔드가 경계에 걸친 예약도
 * 응답에 담기 때문에(완료 조건 5) 시작이 음수이거나 끝이 기간을 넘는 경우가 정상이다.
 */
function Bar({
  bar,
  gridFrom,
  totalDays,
}: {
  bar: ReservationBar;
  gridFrom: string;
  totalDays: number;
}) {
  const rawStart = daysBetween(gridFrom, bar.checkIn);
  const rawEnd = daysBetween(gridFrom, bar.checkOut);

  const start = Math.max(0, rawStart);
  const end = Math.min(totalDays, rawEnd);
  if (end <= start) {
    return null;
  }

  const isHold = bar.status === 'HOLD';

  return (
    <div
      data-testid={`bar-${bar.id}`}
      title={`${bar.guestName ?? '이름 없음'} · ${channelLabel(bar.channel)} · ${bar.status}`}
      className={cn(
        'pointer-events-auto absolute top-1.5 flex items-center overflow-hidden rounded px-2',
        'text-[11px] text-paper',
      )}
      style={{
        left: `calc(${start} * var(--day-w) + 3px)`,
        width: `calc(${end - start} * var(--day-w) - 6px)`,
        height: 'calc(var(--row-h) - 12px)',
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
