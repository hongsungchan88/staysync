import { useState } from 'react';
import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query';
import type { ReservationBar } from '@/api/schemas';
import { transitionReservation, type Transition } from '@/api/reservations';
import { ApiError } from '@/api/client';
import { channelLabel } from './channels';
import { Button } from '@/components/ui/button';

/**
 * 캘린더에서 예약 막대를 누르면 열리는 최소 패널. 작업지시-15 2절 E.
 *
 * 새 화면을 만들지 않고 `SelectionPanel` 자리를 쓴다. 하는 일은 체크인과 체크아웃
 * 둘뿐이다 — 상태 전이는 4주차부터 서버에 있었고 화면에서 부르는 곳만 없었다.
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

  const mutation = useMutation({
    mutationFn: (transition: Transition) => transitionReservation(bar.id, transition),
    onMutate: () => setError(null),
    onSuccess: (summary) => {
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
        <h2 className="text-sm font-semibold text-ink">{bar.guestName ?? '이름 없음'}</h2>
        <Button size="sm" variant="ghost" onClick={onClose} data-testid="reservation-close">
          닫기
        </Button>
      </div>

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
          <dt className="text-muted">상태</dt>
          <dd className="text-ink" data-testid="reservation-status">
            {STATUS_LABELS[status] ?? status}
          </dd>
        </div>
      </dl>

      <div className="mt-4 flex gap-2 border-t border-rule pt-3">
        {status === 'CONFIRMED' && (
          <Button
            size="sm"
            variant="primary"
            disabled={mutation.isPending}
            onClick={() => mutation.mutate('check-in')}
            data-testid="check-in-button"
          >
            체크인
          </Button>
        )}
        {status === 'CHECKED_IN' && (
          <Button
            size="sm"
            variant="primary"
            disabled={mutation.isPending}
            onClick={() => mutation.mutate('check-out')}
            data-testid="check-out-button"
          >
            체크아웃
          </Button>
        )}
      </div>
      {status === 'CHECKED_IN' && (
        <p className="mt-1 text-[11px] text-muted">체크아웃하면 청소 태스크가 생깁니다.</p>
      )}

      {error && (
        <p className="mt-2 text-xs text-warn" role="alert" data-testid="reservation-error">
          {error}
        </p>
      )}
    </aside>
  );
}
