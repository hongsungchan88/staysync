import { useEffect, useState } from 'react';
import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query';
import type { CalendarGrid, BulkEditResult } from '@/api/schemas';
import { bulkEdit, type BulkEditInput, type PriceMode } from '@/api/bulkEdit';
import { ApiError } from '@/api/client';
import type { SelectionRect } from './selection';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';

/**
 * 선택 범위와 요금·제약 일괄 편집 패널. 계획서 8.4.
 *
 * 8주차는 무엇이 선택됐는지 보여 주는 데까지였고, 9주차에 적용이 붙었다.
 *
 * **미리보기와 적용이 같은 요청이다.** `dryRun` 하나로 갈린다. 화면에서도 두 버튼이
 * 같은 함수를 부르고 그 값만 다르다 — 여기서 갈라 두면 서버가 하나로 묶어 둔 의미가
 * 없어진다.
 */
interface Props {
  data: CalendarGrid;
  rect: SelectionRect | null;
  propertyId: number;
  /** 적용 뒤 다시 불러올 캘린더 쿼리. 요금과 잔여 재고가 함께 바뀐다. */
  calendarKey: QueryKey;
}

/** 화면 표시 순서. 백엔드는 `DayOfWeek` 이름을 받는다. */
const WEEKDAYS = [
  { code: 'MONDAY', label: '월' },
  { code: 'TUESDAY', label: '화' },
  { code: 'WEDNESDAY', label: '수' },
  { code: 'THURSDAY', label: '목' },
  { code: 'FRIDAY', label: '금' },
  { code: 'SATURDAY', label: '토' },
  { code: 'SUNDAY', label: '일' },
] as const;

export function SelectionPanel({ data, rect, propertyId, calendarKey }: Props) {
  if (rect === null) {
    return (
      <aside
        className="w-72 shrink-0 border-l border-rule bg-paper p-4"
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

  return (
    <EditPanel data={data} rect={rect} propertyId={propertyId} calendarKey={calendarKey} />
  );
}

function EditPanel({ data, rect, propertyId, calendarKey }: Props & { rect: SelectionRect }) {
  const client = useQueryClient();

  const units = data.units.slice(rect.fromUnit, rect.toUnit + 1);
  const dates = data.units[0]?.days ?? [];
  const from = dates[rect.fromDate]?.date ?? '';
  const to = dates[rect.toDate]?.date ?? '';
  const days = rect.toDate - rect.fromDate + 1;

  const [weekdays, setWeekdays] = useState<string[]>([]);
  const [priceMode, setPriceMode] = useState<PriceMode | null>(null);
  const [price, setPrice] = useState('');
  const [percent, setPercent] = useState('');
  const [minStay, setMinStay] = useState('');
  const [stopSell, setStopSell] = useState<boolean | null>(null);
  const [preview, setPreview] = useState<BulkEditResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 선택이 바뀌면 앞의 미리보기는 다른 범위의 결과다. 남겨 두면 지금 고른 범위의
  // 결과로 읽힌다.
  useEffect(() => {
    setPreview(null);
    setError(null);
  }, [rect.fromUnit, rect.toUnit, rect.fromDate, rect.toDate]);

  const input = (dryRun: boolean): BulkEditInput => ({
    unitIds: units.map((unit) => unit.id),
    from,
    to,
    weekdays,
    priceMode,
    price: priceMode === 'FIXED' && price !== '' ? Number(price) : null,
    // 화면은 %로 받고 서버는 비율로 받는다. +20 이 0.2 다.
    priceRate: priceMode === 'PERCENT' && percent !== '' ? Number(percent) / 100 : null,
    minStay: minStay === '' ? null : Number(minStay),
    closedToArrival: null,
    stopSell,
    dryRun,
  });

  const nothingToChange =
    (priceMode === null || (priceMode === 'FIXED' ? price === '' : percent === '')) &&
    minStay === '' &&
    stopSell === null;

  const mutation = useMutation({
    mutationFn: (dryRun: boolean) => bulkEdit(propertyId, input(dryRun)),
    onMutate: () => setError(null),
    onSuccess: (result) => {
      setPreview(result);
      if (!result.dryRun) {
        // 적용하면 요금뿐 아니라 잔여 재고 표시도 바뀐다(판매중지). 다시 불러오지
        // 않으면 화면이 원장과 어긋난 채 남는다.
        void client.invalidateQueries({ queryKey: calendarKey });
      }
    },
    onError: (err) =>
      setError(err instanceof ApiError ? err.message : '요청이 실패했습니다.'),
  });

  const toggleWeekday = (code: string) =>
    setWeekdays((current) =>
      current.includes(code) ? current.filter((c) => c !== code) : [...current, code],
    );

  return (
    <aside
      className="flex w-72 shrink-0 flex-col overflow-y-auto border-l border-rule bg-paper p-4"
      data-testid="selection-panel"
    >
      <h2 className="text-sm font-semibold text-ink">요금·제약 편집</h2>

      <dl className="mt-3 space-y-2 text-xs">
        <div>
          <dt className="text-muted">기간</dt>
          <dd className="tabular-nums text-ink" data-testid="selection-range">
            {from} ~ {to} ({days}일)
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
      </dl>

      <fieldset className="mt-4 border-t border-rule pt-3">
        <legend className="sr-only">요일 필터</legend>
        <p className="text-xs text-muted">요일 필터</p>
        <div className="mt-1.5 flex gap-1">
          {WEEKDAYS.map((day) => (
            <button
              key={day.code}
              type="button"
              aria-pressed={weekdays.includes(day.code)}
              data-testid={`weekday-${day.code}`}
              onClick={() => toggleWeekday(day.code)}
              className={cn(
                'h-7 w-7 rounded border text-xs',
                weekdays.includes(day.code)
                  ? 'border-clay bg-clay text-paper'
                  : 'border-rule-strong bg-paper text-body hover:bg-faint',
              )}
            >
              {day.label}
            </button>
          ))}
        </div>
        <p className="mt-1 text-[11px] text-muted">
          {weekdays.length === 0 ? '고르지 않으면 전체 요일에 적용됩니다.' : null}
        </p>
      </fieldset>

      <fieldset className="mt-3">
        <legend className="text-xs text-muted">요금</legend>
        <div className="mt-1.5 flex gap-3 text-xs">
          <label className="flex items-center gap-1">
            <input
              type="radio"
              name="priceMode"
              checked={priceMode === 'FIXED'}
              onChange={() => setPriceMode('FIXED')}
              data-testid="price-mode-fixed"
            />
            고정값
          </label>
          <label className="flex items-center gap-1">
            <input
              type="radio"
              name="priceMode"
              checked={priceMode === 'PERCENT'}
              onChange={() => setPriceMode('PERCENT')}
              data-testid="price-mode-percent"
            />
            기존 대비 %
          </label>
        </div>

        {priceMode === 'FIXED' && (
          <Input
            type="number"
            className="mt-1.5"
            placeholder="250000"
            value={price}
            onChange={(e) => setPrice(e.target.value)}
            data-testid="price-input"
          />
        )}
        {priceMode === 'PERCENT' && (
          <Input
            type="number"
            className="mt-1.5"
            placeholder="20 (내리려면 -20)"
            value={percent}
            onChange={(e) => setPercent(e.target.value)}
            data-testid="percent-input"
          />
        )}
      </fieldset>

      <label className="mt-3 block text-xs text-muted">
        최소 숙박
        <Input
          type="number"
          min={1}
          className="mt-1.5"
          placeholder="바꾸지 않음"
          value={minStay}
          onChange={(e) => setMinStay(e.target.value)}
          data-testid="min-stay-input"
        />
      </label>

      <label className="mt-3 flex items-center gap-2 text-xs text-body">
        <input
          type="checkbox"
          checked={stopSell === true}
          onChange={(e) => setStopSell(e.target.checked ? true : null)}
          data-testid="stop-sell-input"
        />
        판매 중지
      </label>

      {/*
        적용 채널은 두지 않았다. 채널 전파가 P3 라 지금 고른 채널로 나갈 곳이 없다.
        고를 수는 있는데 아무 일도 일어나지 않는 칸을 만들면, 눌러도 안 되는 것인지
        아직 안 만든 것인지 구별할 수 없다.
      */}

      <div className="mt-4 flex gap-2 border-t border-rule pt-3">
        <Button
          size="sm"
          disabled={nothingToChange || mutation.isPending}
          onClick={() => mutation.mutate(true)}
          data-testid="preview-button"
        >
          미리보기
        </Button>
        <Button
          size="sm"
          variant="primary"
          disabled={nothingToChange || mutation.isPending}
          onClick={() => mutation.mutate(false)}
          data-testid="apply-button"
        >
          적용
        </Button>
      </div>

      {error && (
        <p className="mt-2 text-xs text-warn" role="alert" data-testid="bulk-edit-error">
          {error}
        </p>
      )}

      {preview && !error && (
        <p
          className="mt-2 text-xs text-body"
          role="status"
          data-testid={preview.dryRun ? 'bulk-edit-preview' : 'bulk-edit-applied'}
        >
          {preview.dryRun
            ? `판매 단위 ${preview.unitCount}개 × ${preview.dayCount}일 = ${preview.cellCount}칸이 바뀝니다.`
            : `${preview.cellCount}칸을 바꿨습니다.`}
        </p>
      )}

      {nothingToChange && (
        <p className="mt-2 text-[11px] text-muted">바꿀 항목을 하나 이상 정해야 합니다.</p>
      )}
    </aside>
  );
}
