import { useState, type FormEvent } from 'react';
import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { createReservation } from '@/api/reservations';
import { ApiError } from '@/api/client';
import type { CalendarGrid, ReservationSummary } from '@/api/schemas';
import { addDays, toIso } from '@/lib/dates';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

/**
 * 캘린더에서 예약을 입력한다(작업지시-28 B). 전화·현장 예약을 적어 두는 자리이고, 채널이 없는 새 계정이 결제 없이
 * 체크인·체크아웃·청소를 해 볼 수 있는 길이다. 채널은 직접예약(`DIRECT`) — 끌어 옮기기·취소가 그대로 된다.
 *
 * 금액을 비우면 서버가 요금표로 계산한다(위젯과 같은 계산). 재고·최소 숙박 판정도 서버가 하고 그 문구를 그대로 보인다 —
 * 화면이 미리 막는 것은 날짜 순서뿐이다. 보내기 전에 화면 안에서 한 번 더 묻는다(브라우저 모달 없음).
 */
interface Props {
  data: CalendarGrid;
  propertyId: number;
  calendarKey: QueryKey;
  onClose: () => void;
}

export function NewReservationPanel({ data, propertyId, calendarKey, onClose }: Props) {
  const client = useQueryClient();
  const today = toIso(new Date());
  const [unitId, setUnitId] = useState<number | null>(data.units[0]?.id ?? null);
  const [checkIn, setCheckIn] = useState(today);
  const [checkOut, setCheckOut] = useState(addDays(today, 1));
  const [guestName, setGuestName] = useState('');
  const [guestPhone, setGuestPhone] = useState('');
  const [adults, setAdults] = useState('2');
  const [children, setChildren] = useState('0');
  const [amount, setAmount] = useState('');
  const [asking, setAsking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<ReservationSummary | null>(null);

  const unitName = data.units.find((unit) => unit.id === unitId)?.name ?? '';
  const problem =
    unitId === null
      ? '판매 단위가 없습니다. 숙소 화면에서 먼저 넣으세요.'
      : !checkIn || !checkOut || checkOut <= checkIn
        ? '체크아웃은 체크인보다 뒤여야 합니다.'
        : guestName.trim() === ''
          ? '게스트 이름을 적으세요.'
          : amount !== '' && Number(amount) < 0
            ? '금액은 0 이상이어야 합니다.'
            : null;

  const mutation = useMutation({
    mutationFn: () =>
      createReservation({
        propertyId,
        unitId: unitId!,
        checkIn,
        checkOut,
        totalAmount: amount === '' ? null : Number(amount),
        adults: Number(adults),
        children: Number(children),
        guestName: guestName.trim(),
        guestPhone: guestPhone.trim() || undefined,
      }),
    onMutate: () => setError(null),
    onSuccess: (summary) => {
      setAsking(false);
      setCreated(summary);
      void client.invalidateQueries({ queryKey: calendarKey });
    },
    onError: (err) => {
      setAsking(false);
      setError(err instanceof ApiError ? err.message : '요청이 실패했습니다.');
    },
  });

  if (created) {
    return (
      <aside className="w-72 shrink-0 border-l border-rule bg-paper p-4" data-testid="new-reservation-panel">
        <h2 className="text-sm font-semibold text-ink">예약 추가</h2>
        <p role="status" className="mt-2 text-xs text-body" data-testid="new-reservation-done">
          만들었습니다: {unitName} · {created.checkIn} ~ {created.checkOut}
          {created.totalAmount != null && ` · ${created.totalAmount.toLocaleString('ko-KR')}원`}
        </p>
        <p className="mt-1 text-[11px] text-muted">캘린더의 막대를 누르면 체크인·체크아웃을 할 수 있습니다.</p>
        <div className="mt-3 flex gap-2">
          <Button size="sm" onClick={() => setCreated(null)}>
            하나 더
          </Button>
          <Button size="sm" variant="ghost" onClick={onClose} data-testid="new-reservation-close">
            닫기
          </Button>
        </div>
      </aside>
    );
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (problem === null) setAsking(true);
  };

  return (
    <aside
      className="flex w-72 shrink-0 flex-col overflow-y-auto border-l border-rule bg-paper p-4"
      data-testid="new-reservation-panel"
    >
      <div className="flex items-start justify-between">
        <h2 className="text-sm font-semibold text-ink">예약 추가</h2>
        <Button size="sm" variant="ghost" onClick={onClose} data-testid="new-reservation-close">
          닫기
        </Button>
      </div>
      <p className="mt-1 text-[11px] text-muted">전화·현장 예약을 적습니다. 직접예약으로 들어갑니다.</p>

      <form className="mt-3 flex flex-col gap-2 text-xs" onSubmit={submit} aria-label="예약 추가">
        <label className="text-muted">
          판매 단위
          <select
            className="mt-1 block h-9 w-full rounded-md border border-rule-strong bg-paper px-2 text-sm text-ink"
            value={unitId ?? ''}
            onChange={(e) => setUnitId(Number(e.target.value))}
            data-testid="new-reservation-unit"
          >
            {data.units.map((unit) => (
              <option key={unit.id} value={unit.id}>
                {unit.name}
              </option>
            ))}
          </select>
        </label>
        <div className="flex gap-2">
          <label className="text-muted">
            체크인
            <Input type="date" className="mt-1" value={checkIn} onChange={(e) => setCheckIn(e.target.value)} data-testid="new-reservation-check-in" />
          </label>
          <label className="text-muted">
            체크아웃
            <Input type="date" className="mt-1" value={checkOut} onChange={(e) => setCheckOut(e.target.value)} data-testid="new-reservation-check-out" />
          </label>
        </div>
        <label className="text-muted">
          게스트 이름
          <Input className="mt-1" maxLength={120} value={guestName} onChange={(e) => setGuestName(e.target.value)} data-testid="new-reservation-guest" />
        </label>
        <label className="text-muted">
          연락처 (선택)
          <Input className="mt-1" maxLength={40} value={guestPhone} onChange={(e) => setGuestPhone(e.target.value)} data-testid="new-reservation-phone" />
        </label>
        <div className="flex gap-2">
          <label className="text-muted">
            성인
            <Input type="number" min={1} max={99} className="mt-1" value={adults} onChange={(e) => setAdults(e.target.value)} data-testid="new-reservation-adults" />
          </label>
          <label className="text-muted">
            아동
            <Input type="number" min={0} max={99} className="mt-1" value={children} onChange={(e) => setChildren(e.target.value)} data-testid="new-reservation-children" />
          </label>
        </div>
        <label className="text-muted">
          금액 (원)
          <Input
            type="number"
            min={0}
            className="mt-1"
            placeholder="비우면 요금표로 계산"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            data-testid="new-reservation-amount"
          />
        </label>

        {problem && <p className="text-[11px] text-muted" data-testid="new-reservation-problem">{problem}</p>}

        <Button type="submit" size="sm" variant="primary" disabled={problem !== null || mutation.isPending} data-testid="new-reservation-submit">
          예약 추가
        </Button>
      </form>

      {asking && (
        <div role="alertdialog" aria-label="예약 추가 확인" className="mt-2 rounded-md border border-warn p-2 text-xs text-body" data-testid="new-reservation-confirm">
          <p className="font-medium text-ink">
            {guestName.trim()} · {unitName} · {checkIn} ~ {checkOut}
          </p>
          <p className="mt-1">
            직접예약으로 확정합니다. 금액은 {amount === '' ? '요금표로 계산합니다' : `${Number(amount).toLocaleString('ko-KR')}원입니다`}.
          </p>
          <div className="mt-2 flex gap-2">
            <Button size="sm" variant="primary" disabled={mutation.isPending} onClick={() => mutation.mutate()} data-testid="new-reservation-confirm-button">
              확정합니다
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setAsking(false)}>
              취소
            </Button>
          </div>
        </div>
      )}

      {error && (
        <p className="mt-2 text-xs text-warn" role="alert" data-testid="new-reservation-error">
          {error}
        </p>
      )}
    </aside>
  );
}
