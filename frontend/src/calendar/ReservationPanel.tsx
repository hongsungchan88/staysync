import { useState } from 'react';
import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query';
import type { ReservationBar } from '@/api/schemas';
import { transitionReservation, type Transition } from '@/api/reservations';
import { toIso } from '@/lib/dates';
import { ApiError } from '@/api/client';
import { channelLabel, guestLabel, isIcalChannel } from './channels';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

/**
 * 캘린더에서 예약 막대를 누르면 열리는 최소 패널. 작업지시-15 2절 E.
 *
 * 새 화면을 만들지 않고 `SelectionPanel` 자리를 쓴다. 하는 일은 체크인과 체크아웃,
 * 그리고 그 둘의 되돌리기다(작업지시-20 9절) — 전부 화면 안에서 한 번 더 묻는다.
 * 청소 태스크 자동 생성이 체크아웃에 걸려 있어 이것이 없으면 운영 흐름의 절반이
 * 화면에서 보이지 않는다(확인-08 3절 4번).
 *
 * 낙관적 업데이트를 하지 않는다. 옮기기와 달리 되돌릴 화면 상태가 없고, 응답 한 번이면
 * 끝난다. 성공하면 캘린더를 다시 불러온다 — 막대의 상태 표시(체크아웃은 흐리게)가
 * 거기서 바뀐다.
 */
const STATUS_LABELS: Record<string, string> = {
  HOLD: '결제 대기',
  CONFIRMED: '확정',
  CHECKED_IN: '체크인',
  CHECKED_OUT: '체크아웃',
  CANCELLED: '취소',
  EXPIRED: '만료',
  NO_SHOW: '노쇼',
};

interface Action {
  transition: Transition;
  from: string;
  label: string;
  confirmLabel: string;
  testId: string;
  undo: boolean;
  visible: (bar: ReservationBar) => boolean;
  explain: (bar: ReservationBar) => string;
}

/**
 * 상태마다 누를 수 있는 것. 되돌리기 둘은 작업지시-20 9절이다 — 체크인 취소는 퇴실 전이면
 * 언제든(Mews), 체크아웃 되돌리기는 퇴실일 당일까지(OPERA·RoomKey). 날짜 판정은 서버가 하고
 * 화면은 지난 예약에 버튼을 두지 않을 뿐이다.
 */
const ACTIONS: Action[] = [
  {
    transition: 'check-in',
    from: 'CONFIRMED',
    label: '체크인',
    confirmLabel: '체크인합니다',
    testId: 'check-in-button',
    undo: false,
    visible: () => true,
    explain: () => '이 예약을 체크인합니다. 잘못 눌렀으면 퇴실 전까지 체크인을 취소할 수 있습니다.',
  },
  {
    transition: 'check-out',
    from: 'CHECKED_IN',
    label: '체크아웃',
    confirmLabel: '체크아웃합니다',
    testId: 'check-out-button',
    undo: false,
    visible: () => true,
    explain: (bar) =>
      `이 예약을 체크아웃합니다. 청소 태스크가 생기고 인박스에 청소 알림이 남습니다. ` +
      `퇴실일(${bar.checkOut})까지, 청소를 시작하기 전이면 되돌릴 수 있습니다.`,
  },
  {
    transition: 'check-in/undo',
    from: 'CHECKED_IN',
    label: '체크인 취소',
    confirmLabel: '체크인을 취소합니다',
    testId: 'undo-check-in-button',
    undo: true,
    visible: () => true,
    explain: () => '체크인을 취소하고 확정 상태로 돌립니다. 재고는 그대로입니다.',
  },
  {
    transition: 'check-out/undo',
    from: 'CHECKED_OUT',
    label: '체크아웃 되돌리기',
    confirmLabel: '체크아웃을 되돌립니다',
    testId: 'undo-check-out-button',
    undo: true,
    visible: (bar) => toIso(new Date()) <= bar.checkOut,
    explain: () =>
      '체크아웃을 되돌려 투숙 중으로 돌립니다. 아직 시작하지 않은 청소 태스크는 함께 거둡니다. ' +
      '청소가 진행 중이거나 끝났으면 되돌릴 수 없습니다. 인박스 알림은 남습니다.',
  },
];

interface Props {
  bar: ReservationBar;
  calendarKey: QueryKey;
  onClose: () => void;
}

export function ReservationPanel({ bar, calendarKey, onClose }: Props) {
  const client = useQueryClient();
  // 전이 뒤 막대 데이터는 캘린더 재조회가 끝나야 바뀐다. 그 사이 버튼이 옛 상태로
  // 남지 않도록 응답의 상태를 여기서 먼저 반영한다.
  const [status, setStatus] = useState(bar.status);
  const [error, setError] = useState<string | null>(null);
  // 확인을 기다리는 조작과 되돌리기 사유.
  const [asking, setAsking] = useState<Transition | null>(null);
  const [reason, setReason] = useState('');
  const current = ACTIONS.find((action) => action.transition === asking && action.from === status);

  const mutation = useMutation({
    mutationFn: ({ transition, reason }: { transition: Transition; reason?: string }) =>
      transitionReservation(bar.id, transition, reason),
    onMutate: () => setError(null),
    onSuccess: (summary) => {
      setAsking(null);
      setStatus(summary.status);
      void client.invalidateQueries({ queryKey: calendarKey });
    },
    onError: (err) =>
      setError(err instanceof ApiError ? err.message : '요청이 실패했습니다.'),
  });

  return (
    <aside
      className="flex w-72 shrink-0 flex-col border-l border-rule bg-paper p-4"
      data-testid="reservation-panel"
    >
      <div className="flex items-start justify-between">
        <h2 className="text-sm font-semibold text-ink">{guestLabel(bar)}</h2>
        <Button size="sm" variant="ghost" onClick={onClose} data-testid="reservation-close">
          닫기
        </Button>
      </div>
      {!bar.guestName && isIcalChannel(bar.channel) && (
        // 예약 상세(DESCRIPTION)는 읽지 않기로 했다(계획서 15.3). 이름을 채우는 대신 이유를 적는다.
        <p className="mt-1 text-[11px] text-muted" data-testid="reservation-name-reason">
          {channelLabel(bar.channel)} iCal 은 게스트 이름과 금액을 보내 주지 않습니다. 게스트
          정보는 {channelLabel(bar.channel)} 예약 화면에서 확인하세요.
        </p>
      )}

      <dl className="mt-3 space-y-2 text-xs">
        <div>
          <dt className="text-muted">기간</dt>
          <dd className="tabular-nums text-ink">
            {bar.checkIn} ~ {bar.checkOut}
          </dd>
        </div>
        <div>
          <dt className="text-muted">채널</dt>
          <dd className="text-ink">{channelLabel(bar.channel)}</dd>
        </div>
        <div>
          <dt className="text-muted">금액</dt>
          {/* 모르는 금액을 0원으로 쓰지 않는다(작업지시-16). */}
          <dd className="tabular-nums text-ink" data-testid="reservation-amount">
            {bar.amount == null ? '미상' : `${bar.amount.toLocaleString('ko-KR')}원`}
          </dd>
        </div>
        {bar.adults != null && (
          <div>
            <dt className="text-muted">인원</dt>
            <dd className="text-ink" data-testid="reservation-guests">
              성인 {bar.adults}
              {bar.children ? ` · 아동 ${bar.children}` : ''}
            </dd>
          </div>
        )}
        <div>
          <dt className="text-muted">상태</dt>
          <dd className="text-ink" data-testid="reservation-status">
            {STATUS_LABELS[status] ?? status}
          </dd>
        </div>
      </dl>

      <div className="mt-4 flex flex-wrap gap-2 border-t border-rule pt-3">
        {ACTIONS.filter((action) => action.from === status && action.visible(bar)).map((action) => (
          <Button
            key={action.transition}
            size="sm"
            variant={action.undo ? 'ghost' : 'primary'}
            disabled={mutation.isPending}
            onClick={() => {
              setError(null);
              setReason('');
              setAsking(asking === action.transition ? null : action.transition);
            }}
            aria-expanded={asking === action.transition}
            data-testid={action.testId}
          >
            {action.label}
          </Button>
        ))}
      </div>

      {current && (
        // 잘못 누르면 되돌리기 번거로운 조작이라 한 번 더 묻는다. 브라우저 모달은 쓰지 않는다.
        <div
          role="alertdialog"
          aria-label={`${current.label} 확인`}
          className="mt-2 rounded-md border border-warn p-2 text-xs text-body"
          data-testid="transition-confirm"
        >
          <p className="font-medium text-ink">
            {guestLabel(bar)} · {bar.checkIn} ~ {bar.checkOut}
          </p>
          <p className="mt-1">{current.explain(bar)}</p>
          {current.undo && (
            <label className="mt-2 block text-muted">
              사유 (기록에 남습니다)
              <Input
                className="mt-1"
                value={reason}
                maxLength={200}
                onChange={(event) => setReason(event.target.value)}
                data-testid="undo-reason"
              />
            </label>
          )}
          <div className="mt-2 flex gap-2">
            <Button
              size="sm"
              variant="primary"
              disabled={mutation.isPending || (current.undo && reason.trim() === '')}
              onClick={() =>
                mutation.mutate({
                  transition: current.transition,
                  reason: current.undo ? reason.trim() : undefined,
                })
              }
              data-testid="transition-confirm-button"
            >
              {current.confirmLabel}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setAsking(null)}>
              취소
            </Button>
          </div>
        </div>
      )}

      {error && (
        <p className="mt-2 text-xs text-warn" role="alert" data-testid="reservation-error">
          {error}
        </p>
      )}
    </aside>
  );
}
