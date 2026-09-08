import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  createHold,
  fetchAvailability,
  fetchReservationStatus,
  preparePayment,
  PublicApiError,
  type HoldResult,
  type PublicDay,
  type PublicUnit,
} from '@/api/publicBooking';
import { loadPortOne } from './portone';

/**
 * 직접예약 위젯. 계획서 8.7 의 `/widget/:propertyId` 다.
 *
 * <h2>공개 화면이다</h2>
 *
 * 로그인 없이 열린다. `App.tsx` 에서 **토큰 검사보다 앞에** 놓여 있다 — 뒤에 두면
 * 세션 없는 방문자에게 로그인 화면이 뜬다.
 *
 * 임베드는 **iframe 한 줄**이다(작업지시 13 의 5절 3번). 스크립트 로더를 만들면
 * 호스트 페이지의 CSS 격리, 높이 자동 조절, 버전 관리, CSP 가 따라오고 위젯이 하는
 * 일보다 로더가 커진다. iframe 은 격리가 기본이다.
 *
 * <h2>서버가 보낸 값만 보여 준다</h2>
 *
 * 요금도 가용도 합계도 서버가 구한 값이다. 화면은 더하기만 하고, 그 합계를
 * `quotedAmount` 로 **대조용으로** 돌려보낸다. 서버가 다시 계산해 다르면 거절한다 —
 * 요금이 그 사이 바뀌었거나 값이 조작됐다는 뜻이고, 어느 쪽이든 그 금액으로 결제창을
 * 열면 안 된다.
 */
export function WidgetPage() {
  const { propertyId } = useParams();
  const id = Number(propertyId);

  const [checkIn, setCheckIn] = useState('');
  const [checkOut, setCheckOut] = useState('');
  const [adults, setAdults] = useState(2);
  const [children, setChildren] = useState(0);
  const [unitId, setUnitId] = useState<number | null>(null);
  const [guestName, setGuestName] = useState('');
  const [guestPhone, setGuestPhone] = useState('');
  const [guestEmail, setGuestEmail] = useState('');
  const [held, setHeld] = useState<HoldResult | null>(null);
  const [rejected, setRejected] = useState<string | null>(null);

  // 마지막 밤까지 묻는다. 체크아웃일은 숙박하지 않으므로 재고를 차지하지 않는다.
  const lastNight = checkOut ? addDays(checkOut, -1) : '';
  const rangeReady = Boolean(checkIn && checkOut && checkIn < checkOut);

  const availability = useQuery({
    queryKey: ['public-availability', id, checkIn, lastNight] as const,
    queryFn: () => fetchAvailability(id, checkIn, lastNight),
    enabled: Number.isFinite(id) && rangeReady,
  });

  const units = availability.data ?? [];
  const selected = units.find((unit) => unit.id === unitId) ?? null;
  const quote = useMemo(() => (selected ? quoteOf(selected) : null), [selected]);

  const hold = useMutation({
    mutationFn: () => {
      if (!selected || !quote) {
        throw new Error('판매 단위를 먼저 고릅니다.');
      }
      return createHold(id, {
        unitId: selected.id,
        checkIn,
        checkOut,
        quotedAmount: quote.total,
        adults,
        children,
        guestName,
        guestPhone: guestPhone || undefined,
        guestEmail: guestEmail || undefined,
      });
    },
    onSuccess: (result) => {
      setRejected(null);
      setHeld(result);
    },
    onError: (error) => {
      // 거절 이유를 그대로 보여 준다. 팔 수 없는 날짜인지, 최소 숙박일인지, 금액이
      // 어긋났는지를 손님이 알아야 다음 행동을 고를 수 있다.
      setRejected(
        error instanceof PublicApiError
          ? error.message
          : '예약을 잡지 못했습니다. 잠시 뒤 다시 시도해 주세요.',
      );
    },
  });

  if (!Number.isFinite(id)) {
    return <Shell>숙소를 찾을 수 없습니다.</Shell>;
  }

  if (held) {
    return (
      <Shell>
        <AfterHold held={held} />
      </Shell>
    );
  }

  return (
    <Shell>
      <h1 className="text-lg font-semibold text-ink">예약하기</h1>

      <section className="flex flex-col gap-3" aria-label="날짜와 인원">
        <div className="flex flex-wrap gap-3">
          <Field label="체크인">
            <input
              type="date"
              aria-label="체크인"
              className={inputClass}
              value={checkIn}
              onChange={(event) => {
                setCheckIn(event.target.value);
                setUnitId(null);
              }}
            />
          </Field>
          <Field label="체크아웃">
            <input
              type="date"
              aria-label="체크아웃"
              className={inputClass}
              value={checkOut}
              onChange={(event) => {
                setCheckOut(event.target.value);
                setUnitId(null);
              }}
            />
          </Field>
        </div>
        <div className="flex flex-wrap gap-3">
          <Field label="성인">
            <input
              type="number"
              aria-label="성인"
              min={1}
              max={30}
              className={inputClass}
              value={adults}
              onChange={(event) => setAdults(Number(event.target.value))}
            />
          </Field>
          <Field label="아동">
            <input
              type="number"
              aria-label="아동"
              min={0}
              max={30}
              className={inputClass}
              value={children}
              onChange={(event) => setChildren(Number(event.target.value))}
            />
          </Field>
        </div>
      </section>

      {checkIn && checkOut && !rangeReady && (
        <p role="alert" className={noticeClass}>
          체크아웃은 체크인 다음 날부터입니다.
        </p>
      )}

      {availability.isLoading && <p className="text-sm text-muted">가용 여부를 확인하는 중입니다…</p>}
      {availability.isError && (
        <p role="alert" className={noticeClass}>
          가용 여부를 불러오지 못했습니다.
        </p>
      )}

      {rangeReady && availability.isSuccess && (
        <section className="flex flex-col gap-2" aria-label="객실">
          <h2 className="text-sm font-semibold text-ink">객실</h2>
          {units.length === 0 && <p className="text-sm text-muted">판매 중인 객실이 없습니다.</p>}
          {units.map((unit) => (
            <UnitOption
              key={unit.id}
              unit={unit}
              checked={unitId === unit.id}
              onSelect={() => {
                setUnitId(unit.id);
                setRejected(null);
              }}
            />
          ))}
        </section>
      )}

      {selected && quote && quote.sellable && (
        <section className="flex flex-col gap-3" aria-label="예약자 정보">
          <h2 className="text-sm font-semibold text-ink">예약자 정보</h2>
          <Field label="이름">
            <input
              aria-label="이름"
              className={inputClass}
              value={guestName}
              onChange={(event) => setGuestName(event.target.value)}
            />
          </Field>
          <Field label="연락처">
            <input
              aria-label="연락처"
              className={inputClass}
              value={guestPhone}
              onChange={(event) => setGuestPhone(event.target.value)}
            />
          </Field>
          <Field label="이메일">
            <input
              type="email"
              aria-label="이메일"
              className={inputClass}
              value={guestEmail}
              onChange={(event) => setGuestEmail(event.target.value)}
            />
          </Field>

          <p className="text-sm text-ink">
            {quote.nights}박 합계 <strong>{won(quote.total)}</strong>
          </p>

          {rejected && (
            <p role="alert" className={noticeClass}>
              {rejected}
            </p>
          )}

          <button
            type="button"
            className="inline-flex h-10 items-center justify-center rounded-md bg-ink px-4 text-sm font-medium text-paper disabled:opacity-50"
            // 이름이 없으면 서버가 거절한다. 보내기 전에 막아 왕복을 아낀다 —
            // 판정 자체는 서버가 한다.
            disabled={!guestName.trim() || hold.isPending}
            onClick={() => hold.mutate()}
          >
            {hold.isPending ? '잡는 중…' : '예약 잡기'}
          </button>
        </section>
      )}
    </Shell>
  );
}

/**
 * 홀드가 끝난 뒤의 화면. 결제까지 여기서 한다.
 *
 * <b>단계가 셋이다.</b> 잡힘 → 결제창 → 확정 확인. 마지막이 따로 있는 이유는
 * <b>확정이 웹훅으로만 일어나기 때문이다</b> — 결제창이 성공을 돌려줘도 우리 예약은
 * 포트원이 서버로 웹훅을 보내야 확정된다. 결제창의 성공만 믿고 "예약 완료"를 띄우면
 * 아직 홀드인 예약을 확정으로 보여 주게 되고, 웹훅이 끝내 오지 않으면 그 예약은
 * 15분 뒤 조용히 사라진다.
 */
function AfterHold({ held }: { held: HoldResult }) {
  const [phase, setPhase] = useState<'held' | 'paying' | 'confirming' | 'done'>('held');
  const [failed, setFailed] = useState<string | null>(null);

  const pay = async () => {
    setFailed(null);
    setPhase('paying');
    try {
      const setup = await preparePayment(held.confirmationCode);
      const portone = await loadPortOne();
      const result = await portone.requestPayment({
        storeId: setup.storeId,
        channelKey: setup.channelKey,
        paymentId: setup.paymentId,
        orderName: setup.orderName,
        totalAmount: setup.amount,
        currency: 'KRW',
        payMethod: 'CARD',
      });
      // 결제창은 실패했을 때만 code 를 준다. 손님이 창을 닫은 경우도 여기다.
      if (result?.code) {
        setFailed(result.message ?? '결제가 완료되지 않았습니다.');
        setPhase('held');
        return;
      }
      setPhase('confirming');
      const confirmed = await waitForConfirmed(held.confirmationCode);
      if (confirmed) {
        setPhase('done');
      } else {
        // 결제는 됐는데 웹훅이 아직 안 왔을 수 있다. **실패로 단정하지 않는다** —
        // 결제를 두 번 하게 만드는 것이 가장 나쁜 안내다.
        setFailed('결제는 접수되었으나 확정 확인이 늦어지고 있습니다. '
          + '잠시 뒤 확인번호로 문의해 주세요.');
        setPhase('held');
      }
    } catch (error) {
      setFailed(error instanceof PublicApiError
        ? error.message
        : '결제를 시작하지 못했습니다. 잠시 뒤 다시 시도해 주세요.');
      setPhase('held');
    }
  };

  if (phase === 'done') {
    return (
      <section className="flex flex-col gap-3" aria-label="예약 완료">
        <h1 className="text-lg font-semibold text-ink">예약이 확정되었습니다</h1>
        <p className="text-sm text-body">
          확인번호 <strong>{held.confirmationCode}</strong>
        </p>
        <p className="text-sm text-body">
          결제 금액 <strong>{won(held.amount)}</strong>
        </p>
      </section>
    );
  }

  return (
    <section className="flex flex-col gap-3" aria-label="결제">
      <h1 className="text-lg font-semibold text-ink">예약을 잡았습니다</h1>
      <p className="text-sm text-body">
        확인번호 <strong>{held.confirmationCode}</strong>
      </p>
      <p className="text-sm text-body">
        결제 금액 <strong>{won(held.amount)}</strong>
      </p>
      {/*
        만료 시각을 반드시 보여 준다. 15분 뒤 사라지는 점유라 언제까지 결제해야
        하는지 모르면 손님이 자리를 비운 사이 예약이 조용히 없어진다.
      */}
      <p className="text-sm text-clay">{localTime(held.expiresAt)}까지 결제해야 합니다.</p>

      {failed && (
        <p role="alert" className={noticeClass}>
          {failed}
        </p>
      )}

      {phase === 'confirming' ? (
        <p className="text-sm text-muted">결제를 확인하는 중입니다…</p>
      ) : (
        <button
          type="button"
          className="inline-flex h-10 items-center justify-center rounded-md bg-ink px-4 text-sm font-medium text-paper disabled:opacity-50"
          disabled={phase === 'paying'}
          onClick={pay}
        >
          {phase === 'paying' ? '결제창을 여는 중…' : '결제하기'}
        </button>
      )}
    </section>
  );
}

/**
 * 확정될 때까지 서버에 물어본다.
 *
 * <b>결제창의 성공과 우리 확정 사이에는 시차가 있다.</b> 포트원이 웹훅을 보내고
 * 우리가 결제사에 금액을 다시 물어본 뒤에야 확정되므로, 곧바로 물으면 아직 HOLD 다.
 *
 * 무한정 기다리지 않는다. 못 받았다고 실패로 단정하지도 않는다 — 결제를 두 번 하게
 * 만드는 것이 가장 나쁜 안내다.
 */
async function waitForConfirmed(confirmationCode: string): Promise<boolean> {
  for (let attempt = 0; attempt < 15; attempt++) {
    await new Promise((resolve) => setTimeout(resolve, 2000));
    try {
      if ((await fetchReservationStatus(confirmationCode)) === 'CONFIRMED') {
        return true;
      }
    } catch {
      // 한 번 실패했다고 그만두지 않는다. 다음 주기에 다시 물어본다.
    }
  }
  return false;
}

function UnitOption({
  unit,
  checked,
  onSelect,
}: {
  unit: PublicUnit;
  checked: boolean;
  onSelect: () => void;
}) {
  const quote = quoteOf(unit);

  return (
    <label
      className={`flex cursor-pointer items-start gap-3 rounded-md border p-3 ${
        checked ? 'border-clay bg-sand/40' : 'border-rule bg-paper'
      }`}
      data-testid={`unit-${unit.id}`}
    >
      <input
        type="radio"
        name="unit"
        className="mt-1"
        checked={checked}
        disabled={!quote.sellable}
        onChange={onSelect}
      />
      <span className="flex-1">
        <span className="block text-sm font-medium text-ink">{unit.name}</span>
        {quote.sellable ? (
          <span className="block text-sm text-body">
            {quote.nights}박 {won(quote.total)}
          </span>
        ) : (
          // 왜 못 고르는지 적는다. 비활성만 해 두면 손님이 날짜를 바꿔 볼 생각을 못 한다.
          <span className="block text-sm text-muted">{quote.reason}</span>
        )}
      </span>
    </label>
  );
}

// --- 계산 ---------------------------------------------------------------------

interface Quote {
  nights: number;
  total: number;
  sellable: boolean;
  reason: string;
}

/**
 * 그 판매 단위의 합계와 팔 수 있는지.
 *
 * **더하기만 한다.** 가격은 서버가 날짜마다 준 값이고 화면이 요금 규칙을 다시 세우지
 * 않는다. 최종 판정도 서버가 하며, 여기 확인은 손님에게 이유를 보여 주기 위한 것이다.
 */
function quoteOf(unit: PublicUnit): Quote {
  const days = unit.days;
  if (days.length === 0) {
    return { nights: 0, total: 0, sellable: false, reason: '날짜를 고르세요' };
  }
  const blocked = days.find((day) => day.available <= 0 || day.stopSell);
  if (blocked) {
    return { nights: days.length, total: 0, sellable: false, reason: '예약할 수 없는 날짜가 있습니다' };
  }
  const minStay = days[0]?.minStay ?? 1;
  if (days.length < minStay) {
    return {
      nights: days.length,
      total: 0,
      sellable: false,
      reason: `최소 ${minStay}박부터 예약할 수 있습니다`,
    };
  }
  return {
    nights: days.length,
    total: days.reduce((sum: number, day: PublicDay) => sum + day.price, 0),
    sellable: true,
    reason: '',
  };
}

// --- 표시 ---------------------------------------------------------------------

const inputClass =
  'mt-1 h-9 w-40 rounded-md border border-rule-strong bg-paper px-2 text-sm text-ink';
const noticeClass = 'rounded-md border border-clay bg-paper p-3 text-sm text-clay';

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto flex w-full max-w-md flex-col gap-5 p-5">{children}</div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="text-xs text-muted">
      {label}
      {children}
    </label>
  );
}

function won(amount: number): string {
  return `${Math.round(amount).toLocaleString('ko-KR')}원`;
}

/**
 * `YYYY-MM-DD` 를 며칠 옮긴다.
 *
 * **`new Date(문자열)` 로 파싱하지 않는다.** 날짜만 있는 ISO 문자열은 UTC 로 읽히므로
 * 한국 시간대에서 하루가 밀린다. 숫자를 직접 넘겨 지역 시각으로 만든다.
 */
function addDays(date: string, days: number): string {
  const [year, month, day] = date.split('-').map(Number);
  if (!year || !month || !day) {
    return '';
  }
  const at = new Date(year, month - 1, day + days);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}`;
}

/**
 * 서버가 보낸 시각을 이 지역 시각으로 적는다.
 *
 * **ISO 문자열을 잘라 쓰지 않는다.** 15주차에 그렇게 했다가 체크아웃 11시가 새벽
 * 2시로 떴다. 여기는 결제 마감 시각이라 틀리면 손님이 시간을 놓친다.
 */
function localTime(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) {
    return iso;
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(at.getHours())}:${pad(at.getMinutes())}`;
}
