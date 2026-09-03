import type { CalendarGrid } from '@/api/schemas';
import type { SelectionRect } from './selection';

/**
 * 선택 범위를 보여 주는 우측 패널.
 *
 * **이번 주는 무엇이 선택됐는지 보여 주는 데까지다.** 요금·제약 일괄 편집의 적용은
 * 9주차라 적용 버튼을 두지 않는다. 버튼만 먼저 만들어 두면 눌러도 아무 일이 없는 것과
 * 값을 넣었는데 반영되지 않는 것을 구별할 수 없다.
 *
 * 8.4 의 편집 패널이 "적용 대상"에 판매 단위를 여러 개 받으므로, 여러 단위에 걸친 선택을
 * 그대로 목록으로 보여 준다.
 */
interface Props {
  data: CalendarGrid;
  rect: SelectionRect | null;
}

export function SelectionPanel({ data, rect }: Props) {
  if (rect === null) {
    return (
      <aside
        className="w-64 shrink-0 border-l border-rule bg-paper p-4"
        data-testid="selection-panel"
      >
        <h2 className="text-sm font-semibold text-ink">기간 선택</h2>
        <p className="mt-2 text-xs leading-relaxed text-muted">
          달력에서 셀을 끌면 선택한 범위가 여기에 나타납니다. 여러 판매 단위에 걸쳐 고를 수
          있습니다.
        </p>
      </aside>
    );
  }

  const units = data.units.slice(rect.fromUnit, rect.toUnit + 1);
  const dates = data.units[0]?.days ?? [];
  const from = dates[rect.fromDate]?.date ?? '';
  const to = dates[rect.toDate]?.date ?? '';
  const nights = rect.toDate - rect.fromDate + 1;

  return (
    <aside
      className="w-64 shrink-0 overflow-y-auto border-l border-rule bg-paper p-4"
      data-testid="selection-panel"
    >
      <h2 className="text-sm font-semibold text-ink">기간 선택</h2>

      <dl className="mt-3 space-y-2 text-xs">
        <div>
          <dt className="text-muted">기간</dt>
          <dd className="tabular-nums text-ink" data-testid="selection-range">
            {from} ~ {to} ({nights}일)
          </dd>
        </div>
        <div>
          <dt className="text-muted">적용 대상 {units.length}개</dt>
          <dd>
            <ul className="mt-1 space-y-0.5" data-testid="selection-units">
              {units.map((unit) => (
                <li key={unit.id} className="truncate text-ink">
                  {unit.name}
                </li>
              ))}
            </ul>
          </dd>
        </div>
        <div>
          <dt className="text-muted">선택한 칸</dt>
          <dd className="tabular-nums text-ink">{units.length * nights}칸</dd>
        </div>
      </dl>

      <p className="mt-4 border-t border-rule pt-3 text-[11px] leading-relaxed text-muted">
        요금과 제약을 한 번에 바꾸는 것은 9주차입니다. 지금은 선택까지만 됩니다.
      </p>
    </aside>
  );
}
