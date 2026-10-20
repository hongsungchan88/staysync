import { useEffect, useState } from 'react';
import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query';
import type { CalendarGrid, BulkEditResult, ReservationBar } from '@/api/schemas';
import { bulkEdit, type BulkEditInput, type PriceMode } from '@/api/bulkEdit';
import { ApiError } from '@/api/client';
import type { SelectionRect } from './selection';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { daysBetween } from '@/lib/dates';
import { ReservationPanel } from './ReservationPanel';

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
  /** 누른 예약 막대. 있으면 기간 선택보다 먼저다 — 나중에 한 조작이 이긴다. */
  bar?: ReservationBar | null;
  onCloseBar?: () => void;
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

/** 미리보기 문구의 항목 이름. 서버의 `changed` 값을 그대로 옮긴다. */
const FIELD_LABELS: Record<string, string> = {
  price: '요금',
  minStay: '최소 숙박',
  closedToArrival: '체크인 금지',
  stopSell: '판매 중지',
};

/** 판매 중지 셋. 요청의 `stopSell` 값 그대로다 — null 은 바꾸지 않음. */
const STOP_SELL_CHOICES = [
  { value: null, label: '바꾸지 않음', testId: 'stop-sell-keep' },
  { value: true, label: '판매 중지', testId: 'stop-sell-on' },
  { value: false, label: '판매 재개', testId: 'stop-sell-off' },
] as const;

/** 미리보기 문구의 항목 하나. 판매 중지는 무엇으로 바뀌는지까지 적는다. */
function fieldLabel(field: string, stopSell: boolean | null): string {
  if (field === 'stopSell' && stopSell === false) return '판매 재개';
  return FIELD_LABELS[field] ?? field;
}

/**
 * 한 번에 바꿀 수 있는 최대 일수. 서버에는 상한이 없다(작업지시-23 8.1) — 1년 치를 한 번에 고치되
 * 실수로 몇 년을 바꾸지 않게 화면에서 막는다.
 */
const MAX_DAYS = 366;

/** 기간 입력이 틀렸으면 그 이유, 맞으면 null. */
function rangeProblem(from: string, to: string): string | null {
  if (from === '' || to === '') return '시작일과 종료일을 모두 정해야 합니다.';
  if (from > to) return '시작일이 종료일보다 늦습니다.';
  if (daysBetween(from, to) + 1 > MAX_DAYS) return `기간은 ${MAX_DAYS}일까지 정할 수 있습니다.`;
  return null;
}

export function SelectionPanel({ data, rect, propertyId, calendarKey, bar, onCloseBar }: Props) {
  if (bar) {
    // key 로 막대마다 패널을 새로 만든다. 다른 막대를 누르면 앞 막대의 상태·오류가 남지 않는다.
    return (
      <ReservationPanel key={bar.id} bar={bar} calendarKey={calendarKey} onClose={() => onCloseBar?.()} />
    );
  }
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
  const dragFrom = dates[rect.fromDate]?.date ?? '';
  const dragTo = dates[rect.toDate]?.date ?? '';

  // 처음 값은 드래그로 고른 범위이고, 사람이 고치면 그 값이 요청에 실린다. 캘린더에 보이는 30일 밖도 된다.
  const [from, setFrom] = useState(dragFrom);
  const [to, setTo] = useState(dragTo);
  const problem = rangeProblem(from, to);

  const [weekdays, setWeekdays] = useState<string[]>([]);
  const [priceMode, setPriceMode] = useState<PriceMode | null>(null);
  const [price, setPrice] = useState('');
  const [percent, setPercent] = useState('');
  const [minStay, setMinStay] = useState('');
  const [stopSell, setStopSell] = useState<boolean | null>(null);
  // 결과와 함께 그 요청의 기간을 둔다. 화면 밖 날짜를 바꾸는 것이라 눈으로 확인할 길이 이 문구뿐이다.
  const [preview, setPreview] = useState<
    (BulkEditResult & { from: string; to: string; stopSell: boolean | null }) | null
  >(null);
  const [error, setError] = useState<string | null>(null);

  // 다시 끌면 그 범위로 돌아간다.
  useEffect(() => {
    setFrom(dragFrom);
    setTo(dragTo);
  }, [dragFrom, dragTo]);
  // 선택이나 기간이 바뀌면 앞의 미리보기는 다른 범위의 결과다. 남겨 두면 지금 고른 범위의
  // 결과로 읽힌다.
  useEffect(() => {
    setPreview(null);
    setError(null);
  }, [rect.fromUnit, rect.toUnit, from, to]);

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
  // 서버와 같은 하한(작업지시-26 E). 서버가 방어선이고 여기는 보내기 전에 이유를 보여 줄 뿐이다.
  const valueProblem =
    priceMode === 'FIXED' && price !== '' && Number(price) < 0
      ? '요금은 0 이상이어야 합니다.'
      : priceMode === 'PERCENT' && percent !== '' && Number(percent) <= -100
        ? '비율은 -100% 보다 커야 합니다.'
        : null;
  const blocked = nothingToChange || problem !== null || valueProblem !== null;

  const mutation = useMutation({
    mutationFn: async (dryRun: boolean) => ({
      ...(await bulkEdit(propertyId, input(dryRun))),
      from,
      to,
      stopSell,
    }),
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
          <dd className="mt-1 flex items-center gap-1 tabular-nums" data-testid="selection-range">
            <Input
              type="date"
              aria-label="시작일"
              className="h-8 px-1.5 text-xs"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              data-testid="range-from"
            />
            <span className="text-muted">~</span>
            <Input
              type="date"
              aria-label="종료일"
              className="h-8 px-1.5 text-xs"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              data-testid="range-to"
            />
          </dd>
          <p className="mt-1 text-[11px] text-muted" data-testid="range-hint">
            {problem ?? `${daysBetween(from, to) + 1}일. 캘린더에 보이지 않는 날짜도 고칠 수 있습니다.`}
          </p>
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
            min={0}
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
        {valueProblem && (
          <p className="mt-1 text-[11px] text-warn" data-testid="price-problem">
            {valueProblem}
          </p>
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

      {/* 셋 중 고른다(작업지시-25). 체크박스는 풀면 "바꾸지 않음"이라 판매 재개를 보낼 길이 없었다. */}
      <fieldset className="mt-3">
        <legend className="text-xs text-muted">판매 중지</legend>
        <div className="mt-1.5 flex flex-wrap gap-x-3 gap-y-1 text-xs">
          {STOP_SELL_CHOICES.map((choice) => (
            <label key={choice.testId} className="flex items-center gap-1">
              <input
                type="radio"
                name="stopSell"
                checked={stopSell === choice.value}
                onChange={() => setStopSell(choice.value)}
                data-testid={choice.testId}
              />
              {choice.label}
            </label>
          ))}
        </div>
        {stopSell !== null && (
          <p className="mt-1 text-[11px] text-muted" data-testid="stop-sell-hint">
            {stopSell
              ? '고른 날짜를 연결된 채널에서 닫습니다.'
              : '판매 중지를 풉니다. 예약된 날은 그대로 닫혀 있습니다.'}
          </p>
        )}
      </fieldset>

      {/*
        적용 채널은 두지 않았다. 채널 전파가 P3 라 지금 고른 채널로 나갈 곳이 없다.
        고를 수는 있는데 아무 일도 일어나지 않는 칸을 만들면, 눌러도 안 되는 것인지
        아직 안 만든 것인지 구별할 수 없다.
      */}

      <div className="mt-4 flex gap-2 border-t border-rule pt-3">
        <Button
          size="sm"
          disabled={blocked || mutation.isPending}
          onClick={() => mutation.mutate(true)}
          data-testid="preview-button"
        >
          미리보기
        </Button>
        <Button
          size="sm"
          variant="primary"
          disabled={blocked || mutation.isPending}
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
          {preview.dryRun ? '바뀔 것: ' : '바꿨습니다: '}
          {`${preview.from} ~ ${preview.to} · 판매 단위 ${preview.unitCount}개 · ${preview.cellCount}칸 · `}
          {preview.changed.map((field) => fieldLabel(field, preview.stopSell)).join(', ')}
        </p>
      )}

      {nothingToChange && (
        <p className="mt-2 text-[11px] text-muted">바꿀 항목을 하나 이상 정해야 합니다.</p>
      )}
    </aside>
  );
}
