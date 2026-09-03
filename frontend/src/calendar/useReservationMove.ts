import { useCallback } from 'react';
import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { moveReservation } from '@/api/reservations';
import { ApiError } from '@/api/client';
import type { CalendarGrid } from '@/api/schemas';
import { addDays } from '@/lib/dates';

/**
 * 옮길 수 있는 예약 상태.
 *
 * 백엔드 `Reservation.isActive()` 와 같은 집합이다. 취소·만료·체크아웃·노쇼는 날짜를
 * 바꾸는 것이 의미가 없어 도메인이 거절하고, 체크인한 예약도 마찬가지다. 화면에서 잡히기만
 * 하고 놓을 때마다 거절당하면 왜 안 되는지 알 수 없으므로 아예 잡히지 않게 한다.
 *
 * **이 목록이 서버 판정을 대신하지는 않는다.** 여기서 거르는 것은 명백히 불가능한 것뿐이고,
 * 재고가 되는지는 서버만 안다.
 */
export const MOVABLE_STATUSES = new Set(['HOLD', 'CONFIRMED']);

export interface MoveRequest {
  reservationId: number;
  /** 며칠 옮길지. 음수면 앞으로 당긴다. 숙박 일수는 유지한다. */
  dayDelta: number;
}

/** 옮길 자리. 며칠이 아니라 날짜다 — 아래 주석의 이유로 한 번만 계산한다. */
interface MoveTarget {
  reservationId: number;
  checkIn: string;
  checkOut: string;
}

/**
 * 예약 막대를 옮긴다. **낙관적 업데이트이되 서버 판정을 우회하지 않는다.**
 *
 * 놓는 순간 화면을 먼저 옮기고, 서버가 거절하면 되돌린다. 되돌리지 않으면 화면에는
 * 옮겨진 것처럼 보이는데 원장은 그대로인 상태가 남는다. 이 프로젝트가 막으려는
 * 중복예약이 정확히 그렇게 생긴다 — 화면만 보면 정상이고, 드러나는 것은 다른 채널
 * 예약이 들어온 뒤다.
 *
 * 성공해도 캐시를 그대로 두지 않고 다시 불러온다. 옮기면 옛 날짜와 새 날짜의 잔여 재고가
 * 함께 바뀌는데, 낙관적 업데이트는 막대 위치만 고쳤기 때문이다. 셀의 숫자가 원장과
 * 어긋난 채 남으면 화면이 거짓말을 한다.
 */
export function useReservationMove(queryKey: QueryKey, onReject: (reason: string) => void) {
  const client = useQueryClient();

  const mutation = useMutation({
    mutationFn: ({ reservationId, checkIn, checkOut }: MoveTarget) =>
      moveReservation(reservationId, checkIn, checkOut),

    onMutate: async ({ reservationId, checkIn, checkOut }) => {
      // 진행 중인 조회가 끝나면서 낙관적 갱신을 덮어쓰는 것을 막는다.
      await client.cancelQueries({ queryKey });
      const snapshot = client.getQueryData<CalendarGrid>(queryKey);

      client.setQueryData<CalendarGrid>(queryKey, (current) =>
        current === undefined
          ? current
          : {
              ...current,
              reservations: current.reservations.map((bar) =>
                bar.id === reservationId ? { ...bar, checkIn, checkOut } : bar,
              ),
            },
      );

      // 되돌릴 때 쓴다. 이것을 빠뜨리면 실패가 조용히 삼켜진다.
      return { snapshot };
    },

    onError: (error, _variables, context) => {
      if (context?.snapshot) {
        client.setQueryData(queryKey, context.snapshot);
      }
      onReject(reasonOf(error));
    },

    onSettled: () => {
      void client.invalidateQueries({ queryKey });
    },
  });

  /**
   * 옮길 날짜를 **여기서 한 번만** 셈한다.
   *
   * `onMutate` 는 `mutationFn` 보다 먼저 돌고 캐시를 이미 옮겨 놓는다. 두 곳에서 각각
   * "지금 캐시에 있는 날짜 + 며칠"을 계산하면 이동이 두 번 적용돼, 화면은 사흘 뒤로 가는데
   * 서버에는 엿새 뒤가 나간다. 절대 날짜를 만들어 넘기면 이 어긋남이 생길 자리가 없다.
   */
  const move = useCallback(
    ({ reservationId, dayDelta }: MoveRequest) => {
      const bar = client
        .getQueryData<CalendarGrid>(queryKey)
        ?.reservations.find((candidate) => candidate.id === reservationId);
      if (!bar) {
        // 화면에 없는 막대는 끌 수 없다. 여기 오면 캐시 키가 화면과 어긋난 것이다.
        return;
      }
      mutation.mutate({
        reservationId,
        checkIn: addDays(bar.checkIn, dayDelta),
        checkOut: addDays(bar.checkOut, dayDelta),
      });
    },
    [client, mutation, queryKey],
  );

  return { move, isError: mutation.isError, isSuccess: mutation.isSuccess };
}

/**
 * 거절 이유.
 *
 * 서버가 한국어 문장을 준다(`INVENTORY_*` 는 409, 전이 오류는 400). 그대로 보여 주는 것이
 * 프론트에서 문구를 다시 짓는 것보다 정확하다 — 어느 날짜가 막혔는지는 서버만 안다.
 */
function reasonOf(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return '예약을 옮기지 못했습니다. 잠시 뒤 다시 시도해 주세요.';
}
