import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchConflicts, resolveConflict, type Resolution } from '@/api/conflicts';
import { fetchCalendar } from '@/api/calendar';
import { ApiError } from '@/api/client';
import type { Conflict } from '@/api/schemas';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { channelLabel } from '@/calendar/channels';

/**
 * 중복예약 충돌 목록. 계획서 7.4 이고 방어 4계층의 마지막 자리다.
 *
 * **채널 로그가 아니라 별도 화면이다.** 충돌은 채널 하나의 문제가 아니라 그 날짜 그
 * 방의 문제라, 채널을 하나씩 열어 봐야 보이면 운영자가 찾지 못한다.
 *
 * 해소 방법 넷을 제시하는 것이 이 화면의 전부다. **게스트 안내 메시지 초안은 없다** —
 * 계획서 7.4 가 9장 AI 기능에 맡겼고 그건 P5 다.
 */

/** 계획서 7.4 의 넷. 순서는 운영자가 먼저 고려해야 하는 것부터다. */
const RESOLUTIONS: { code: Resolution; label: string; hint: string }[] = [
  { code: 'UPGRADED', label: '상위 판매 단위로 배정', hint: '여유 있는 방으로 예약을 옮긴다' },
  { code: 'RELOCATED', label: '인근 제휴 숙소 안내', hint: '우리 재고 밖이라 기록만 남는다' },
  { code: 'CANCELLED', label: '취소와 보상', hint: '예약을 취소해 재고를 되돌린다' },
  { code: 'ABSORBED', label: '관리자 허용', hint: '실제 여유 객실이 있어 그대로 둔다' },
];

export function ConflictsPage() {
  const conflicts = useQuery({ queryKey: ['conflicts'], queryFn: fetchConflicts });

  return (
    <div className="mx-auto flex h-full w-full max-w-4xl flex-col gap-5 overflow-auto p-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-lg font-semibold text-ink">중복예약 충돌</h1>
        <Link to="/" className="text-sm text-clay underline-offset-2 hover:underline">
          캘린더로
        </Link>
      </header>

      {conflicts.isLoading && <p className="text-sm text-muted">불러오는 중입니다…</p>}

      {conflicts.data?.length === 0 && (
        <p className="rounded-md border border-rule bg-paper p-4 text-sm text-muted">
          해소할 충돌이 없습니다.
        </p>
      )}

      <ul className="flex flex-col gap-3">
        {conflicts.data?.map((conflict) => (
          <ConflictCard key={conflict.id} conflict={conflict} />
        ))}
      </ul>
    </div>
  );
}

function ConflictCard({ conflict }: { conflict: Conflict }) {
  const queryClient = useQueryClient();
  const [resolution, setResolution] = useState<Resolution>('UPGRADED');
  const [reservationId, setReservationId] = useState<number | undefined>(
    conflict.reservations[0]?.id,
  );
  const [targetUnitId, setTargetUnitId] = useState<number | undefined>();
  const [memo, setMemo] = useState('');
  const [error, setError] = useState<string | null>(null);

  // 업그레이드 배정에만 필요하다. 그 날짜의 판매 단위와 남은 재고를 캘린더에서
  // 그대로 읽는다 — 여유가 없는 방으로 옮기면 서버가 거절하고, 운영자는 이유를
  // 고른 뒤에야 알게 된다.
  const day = useQuery({
    queryKey: ['calendar', conflict.propertyId, conflict.stayDate],
    queryFn: () => fetchCalendar(conflict.propertyId, conflict.stayDate, conflict.stayDate),
    enabled: resolution === 'UPGRADED',
  });

  const resolve = useMutation({
    mutationFn: () =>
      resolveConflict(conflict.id, {
        resolution,
        reservationId,
        targetUnitId: resolution === 'UPGRADED' ? targetUnitId : undefined,
        memo: memo || undefined,
      }),
    onSuccess: () => {
      setError(null);
      queryClient.invalidateQueries({ queryKey: ['conflicts'] });
      // 재고가 움직였을 수 있다. 캘린더의 충돌 표시도 함께 사라져야 한다.
      queryClient.invalidateQueries({ queryKey: ['calendar'] });
    },
    onError: (e) =>
      setError(
        e instanceof ApiError
          ? e.message
          : '해소하지 못했습니다. 잠시 뒤 다시 시도하세요.',
      ),
  });

  return (
    <li className="rounded-md border border-warn bg-paper p-4">
      <div className="flex items-baseline justify-between">
        <span className="font-medium text-ink">{conflict.stayDate}</span>
        <span className="text-xs text-warn">{conflict.severity}</span>
      </div>

      <ul className="mt-2 flex flex-col gap-1">
        {conflict.reservations.map((reservation) => (
          <li key={reservation.id} className="flex items-center gap-2 text-xs text-muted">
            <input
              type="radio"
              name={`conflict-${conflict.id}-reservation`}
              checked={reservationId === reservation.id}
              onChange={() => setReservationId(reservation.id)}
              aria-label={`예약 ${reservation.confirmationCode}`}
            />
            <span className="font-mono text-ink">{reservation.confirmationCode}</span>
            <span>{channelLabel(reservation.channelCode ?? 'DIRECT')}</span>
            <span>
              {reservation.checkIn} ~ {reservation.checkOut}
            </span>
            <span>{reservation.status}</span>
          </li>
        ))}
      </ul>

      <div className="mt-3 flex flex-col gap-2 border-t border-rule pt-3">
        <label className="text-xs text-muted">
          해소 방법
          <select
            className="mt-1 w-full rounded-md border border-rule bg-paper p-2 text-sm text-ink"
            value={resolution}
            onChange={(event) => setResolution(event.target.value as Resolution)}
          >
            {RESOLUTIONS.map((option) => (
              <option key={option.code} value={option.code}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        <p className="text-xs text-muted">
          {RESOLUTIONS.find((option) => option.code === resolution)?.hint}
        </p>

        {resolution === 'UPGRADED' && (
          <label className="text-xs text-muted">
            옮길 판매 단위
            <select
              className="mt-1 w-full rounded-md border border-rule bg-paper p-2 text-sm text-ink"
              value={targetUnitId ?? ''}
              onChange={(event) => setTargetUnitId(Number(event.target.value) || undefined)}
            >
              <option value="">고르세요</option>
              {day.data?.units
                .filter((unit) => unit.id !== conflict.unitId)
                .map((unit) => (
                  <option key={unit.id} value={unit.id}>
                    {unit.name} (잔여 {unit.days[0]?.avail ?? 0})
                  </option>
                ))}
            </select>
          </label>
        )}

        <label className="text-xs text-muted">
          메모 (선택)
          <Input
            className="mt-1"
            value={memo}
            onChange={(event) => setMemo(event.target.value)}
            placeholder="무엇을 했는지 남겨 두면 다음 사람이 안다"
          />
        </label>

        {error && <p className="text-xs text-warn">{error}</p>}

        <Button
          variant="primary"
          onClick={() => resolve.mutate()}
          disabled={
            resolve.isPending ||
            !reservationId ||
            (resolution === 'UPGRADED' && !targetUnitId)
          }
        >
          해소하기
        </Button>
      </div>
    </li>
  );
}
