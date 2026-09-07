import { useCallback } from 'react';
import { useMutation, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { patchOpsTask } from '@/api/opsTasks';
import { ApiError } from '@/api/client';
import type { OpsTask, TaskStatus } from '@/api/schemas';

export interface TaskMove {
  taskId: number;
  next: TaskStatus;
}

/**
 * 태스크를 칸 사이로 옮긴다. **낙관적 업데이트이되 서버 판정을 우회하지 않는다.**
 *
 * 8주차 예약 막대 드래그와 같은 규칙이다({@code useReservationMove}). 놓는 순간 화면을
 * 먼저 옮기고, 서버가 거절하면 되돌리고 이유를 보여 준다. 되돌리지 않으면 화면에는
 * 완료로 보이는데 판매 단위는 여전히 더러운 상태가 남고, **그 화면은 정상으로 보인다.**
 *
 * 성공해도 캐시를 그대로 두지 않고 다시 불러온다. 완료 처리는 `completedAt` 과
 * 기한 초과 표시까지 바꾸는데 낙관적 갱신은 칸만 옮겼기 때문이다.
 */
export function useTaskMove(queryKey: QueryKey, onReject: (reason: string) => void) {
  const client = useQueryClient();

  const mutation = useMutation({
    mutationFn: ({ taskId, next }: TaskMove) => patchOpsTask(taskId, { status: next }),

    onMutate: async ({ taskId, next }) => {
      // 진행 중인 조회가 끝나면서 낙관적 갱신을 덮어쓰는 것을 막는다.
      await client.cancelQueries({ queryKey });
      const snapshot = client.getQueryData<OpsTask[]>(queryKey);

      client.setQueryData<OpsTask[]>(queryKey, (current) =>
        current?.map((task) => (task.id === taskId ? { ...task, status: next } : task)),
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

  const move = useCallback(
    ({ taskId, next }: TaskMove) => {
      const task = client.getQueryData<OpsTask[]>(queryKey)?.find((t) => t.id === taskId);
      if (!task || task.status === next) {
        // 제자리에 놓은 것은 조작이 아니다. 왕복도 낙관적 갱신도 만들지 않는다.
        return;
      }
      mutation.mutate({ taskId, next });
    },
    [client, mutation, queryKey],
  );

  return { move, isPending: mutation.isPending };
}

function reasonOf(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  return '태스크를 옮기지 못했습니다.';
}
