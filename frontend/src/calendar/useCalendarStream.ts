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
export function useCalendarStream(calendarKey: QueryKey, propertyId: number | null) {
  const client = useQueryClient();

  useEffect(() => {
    if (propertyId === null) {
      return;
    }

    const refetch = () => {
      void client.invalidateQueries({ queryKey: calendarKey });
    };

    return subscribeCalendar({
      onEvent: (event) => {
        // 조직 안에 숙소가 여럿일 수 있다. 지금 보고 있는 숙소의 사건만 쓴다.
        // 서버가 조직으로 좁혀 주지만, 그건 남의 조직을 막는 것이지 숙소를 가르지 않는다.
        if (event.propertyId === propertyId) {
          refetch();
        }
      },
      onReconnect: refetch,
    });
    // calendarKey 는 배열이라 매 렌더 새 참조다. 문자열로 굳혀야 구독이 매번 끊겼다
    // 붙지 않는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, propertyId, JSON.stringify(calendarKey)]);
}
