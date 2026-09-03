import { useEffect } from 'react';
import { useQueryClient, type QueryKey } from '@tanstack/react-query';
import { subscribeCalendar } from '@/api/calendarStream';

/**
 * 열려 있는 캘린더를 실시간으로 따라가게 한다. 계획서 8.2.
 *
 * **받은 사건의 내용을 화면에 반영하지 않는다.** 다시 조회할 뿐이다. 사건에 담긴
 * 변경을 화면이 직접 적용하게 만들면, Outbox 의 최소 1회 전달과 순서 보장을 화면
 * 쪽에도 다시 구현해야 한다 — 같은 사건이 두 번 와도 되고 순서가 뒤바뀌어도 되는
 * 코드를 프론트에 또 쓰는 셈이다. 다시 조회하면 그 문제가 통째로 사라진다.
 *
 * 재연결도 같은 자리로 떨어진다. 끊긴 동안 놓친 사건을 서버가 재생해 주지 않고,
 * 다시 붙는 순간 한 번 조회하면 그동안의 변경이 전부 들어온다.
 */

/**
 * 다시 조회 사이의 최소 간격.
 *
 * **사건 하나에 조회 하나로 두면 안 된다.** 사건은 몰려서 온다 — 앱이 한동안 꺼져
 * 있다가 켜지면 릴레이가 밀린 이벤트를 주기당 100건씩 쏟아내고, P3 의 채널 동기화도
 * 같은 모양이다. 그때마다 2,700셀을 다시 받으면 브라우저가 `ERR_INSUFFICIENT_RESOURCES`
 * 로 죽는다. **실제로 그렇게 죽는 것을 보고 넣은 값이다.**
 *
 * 짧게 잡은 이유는 "즉시 반영"이 이 기능의 요구사항이기 때문이다. 사건 하나만 오면
 * 곧바로 조회하고, 몰려 올 때만 묶인다.
 */
const REFETCH_COOLDOWN_MS = 500;

export function useCalendarStream(calendarKey: QueryKey, propertyId: number | null) {
  const client = useQueryClient();

  useEffect(() => {
    if (propertyId === null) {
      return;
    }

    // 첫 사건에는 곧바로 응답하고, 쿨다운 동안 더 온 것은 끝에서 한 번으로 묶는다.
    // 뒤에만 몰아 두면(단순 디바운스) 사건이 끊이지 않을 때 영영 조회하지 않는다.
    let cooling: ReturnType<typeof setTimeout> | undefined;
    let missed = false;

    const refetchNow = () => {
      void client.invalidateQueries({ queryKey: calendarKey });
    };

    const scheduleCooldown = () => {
      cooling = setTimeout(() => {
        cooling = undefined;
        if (missed) {
          missed = false;
          refetchNow();
          scheduleCooldown();
        }
      }, REFETCH_COOLDOWN_MS);
    };

    const requestRefetch = () => {
      if (cooling) {
        missed = true;
        return;
      }
      refetchNow();
      scheduleCooldown();
    };

    const stop = subscribeCalendar({
      onEvent: (event) => {
        // 조직 안에 숙소가 여럿일 수 있다. 지금 보고 있는 숙소의 사건만 쓴다.
        // 서버가 조직으로 좁혀 주지만, 그건 남의 조직을 막는 것이지 숙소를 가르지 않는다.
        if (event.propertyId === propertyId) {
          requestRefetch();
        }
      },
      onReconnect: requestRefetch,
    });

    return () => {
      clearTimeout(cooling);
      stop();
    };
    // calendarKey 는 배열이라 매 렌더 새 참조다. 문자열로 굳혀야 구독이 매번 끊겼다
    // 붙지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, propertyId, JSON.stringify(calendarKey)]);
}
