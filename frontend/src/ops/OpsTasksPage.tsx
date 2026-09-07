import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { fetchOpsTasks, type BoardFilter } from '@/api/opsTasks';
import { TASK_STATUSES, type OpsTask, type TaskStatus } from '@/api/schemas';
import { Input } from '@/components/ui/input';
import { useTaskMove } from './useTaskMove';

/**
 * 청소 태스크 칸반. 계획서 8.6 의 `/ops/tasks` 다.
 *
 * **칸 사이 이동이 곧 상태 변경이다.** 8주차 예약 막대와 같은 규칙으로,
 * 낙관적 업데이트를 하되 서버가 진실이고 거절되면 되돌린 뒤 이유를 보여 준다.
 *
 * **드래그로만 되는 조작을 만들지 않는다.** 마우스가 없으면 못 쓰는 기능이 된다 —
 * 8주차에 정한 것이 그대로 적용된다. dnd-kit 의 키보드 센서와, 카드마다 있는
 * 칸 선택 목록이 같은 일을 한다.
 */
const COLUMN_LABELS: Record<TaskStatus, string> = {
  TODO: '할 일',
  IN_PROGRESS: '진행 중',
  DONE: '완료',
  BLOCKED: '막힘',
};

export function OpsTasksPage() {
  const [filter, setFilter] = useState<BoardFilter>({});
  const [rejected, setRejected] = useState<string | null>(null);

  const queryKey = useMemo(
    () => ['ops-tasks', filter.from ?? '', filter.to ?? '', filter.assignee ?? ''] as const,
    [filter.from, filter.to, filter.assignee],
  );
  const tasks = useQuery({ queryKey, queryFn: () => fetchOpsTasks(filter) });
  const { move } = useTaskMove(queryKey, setRejected);

  const sensors = useSensors(useSensor(PointerSensor), useSensor(KeyboardSensor));

  const handleDragEnd = (event: DragEndEvent) => {
    const next = event.over?.id;
    if (typeof next !== 'string') {
      // 칸 밖에 놓았다. 조작이 아니다.
      return;
    }
    setRejected(null);
    move({ taskId: Number(event.active.id), next: next as TaskStatus });
  };

  const byStatus = useMemo(() => group(tasks.data ?? []), [tasks.data]);

  return (
    <div className="mx-auto flex h-full w-full max-w-6xl flex-col gap-5 overflow-auto p-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-lg font-semibold text-ink">청소 태스크</h1>
        <Link to="/" className="text-sm text-clay underline-offset-2 hover:underline">
          캘린더로
        </Link>
      </header>

      <section className="flex flex-wrap items-end gap-3" aria-label="필터">
        <label className="text-xs text-muted">
          시작
          <Input
            type="date"
            className="mt-1"
            aria-label="시작 날짜"
            value={filter.from ?? ''}
            onChange={(event) =>
              setFilter((current) => ({ ...current, from: event.target.value || undefined }))
            }
          />
        </label>
        <label className="text-xs text-muted">
          끝
          <Input
            type="date"
            className="mt-1"
            aria-label="끝 날짜"
            value={filter.to ?? ''}
            onChange={(event) =>
              setFilter((current) => ({ ...current, to: event.target.value || undefined }))
            }
          />
        </label>
        <label className="text-xs text-muted">
          담당자
          <Input
            className="mt-1"
            aria-label="담당자"
            placeholder="이름"
            value={filter.assignee ?? ''}
            onChange={(event) =>
              setFilter((current) => ({ ...current, assignee: event.target.value || undefined }))
            }
          />
        </label>
      </section>

      {rejected && (
        // 낙관적 업데이트는 실패해도 화면이 잠깐 정상으로 보인다. 되돌리는 것만으로는
        // 사용자가 무슨 일이 있었는지 알 수 없다. 8주차와 같은 자리다.
        <p role="alert" className="rounded-md border border-clay bg-paper p-3 text-sm text-clay">
          {rejected}
        </p>
      )}

      {tasks.isLoading && <p className="text-sm text-muted">불러오는 중입니다…</p>}

      <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {TASK_STATUSES.map((status) => (
            <Column key={status} status={status} tasks={byStatus[status]} onMove={move} />
          ))}
        </div>
      </DndContext>
    </div>
  );
}

function Column({
  status,
  tasks,
  onMove,
}: {
  status: TaskStatus;
  tasks: OpsTask[];
  onMove: (move: { taskId: number; next: TaskStatus }) => void;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: status });

  return (
    <section
      ref={setNodeRef}
      aria-label={COLUMN_LABELS[status]}
      data-testid={`column-${status}`}
      className={`flex min-h-40 flex-col gap-2 rounded-md border p-3 ${
        isOver ? 'border-clay bg-sand/40' : 'border-rule bg-paper'
      }`}
    >
      <h2 className="text-sm font-semibold text-ink">
        {COLUMN_LABELS[status]} <span className="text-muted">({tasks.length})</span>
      </h2>
      {tasks.map((task) => (
        <TaskCard key={task.id} task={task} onMove={onMove} />
      ))}
    </section>
  );
}

function TaskCard({
  task,
  onMove,
}: {
  task: OpsTask;
  onMove: (move: { taskId: number; next: TaskStatus }) => void;
}) {
  const { attributes, listeners, setNodeRef, transform } = useDraggable({ id: String(task.id) });

  return (
    <article
      ref={setNodeRef}
      data-testid={`task-${task.id}`}
      style={transform ? { transform: `translate3d(${transform.x}px, ${transform.y}px, 0)` } : undefined}
      className={`rounded-md border bg-paper p-2 text-sm ${
        task.overdue ? 'border-clay' : 'border-rule'
      }`}
      {...listeners}
      {...attributes}
    >
      <p className="font-medium text-ink">{task.unitName ?? '판매 단위 없음'}</p>
      <p className="text-xs text-muted">{dueLabel(task)}</p>
      {task.assigneeName && <p className="text-xs text-muted">담당 {task.assigneeName}</p>}
      {task.overdue && (
        <p className="text-xs font-semibold text-clay" data-testid={`overdue-${task.id}`}>
          기한 지남
        </p>
      )}

      {/* 드래그로만 되면 마우스가 없는 사람은 못 쓴다. 같은 조작을 목록으로도 연다. */}
      <label className="mt-2 block text-xs text-muted">
        칸 옮기기
        <select
          className="mt-1 h-8 w-full rounded-md border border-rule-strong bg-paper px-2 text-sm text-ink"
          aria-label={`${task.unitName ?? '태스크'} 칸 옮기기`}
          value={task.status}
          onChange={(event) => onMove({ taskId: task.id, next: event.target.value as TaskStatus })}
        >
          {TASK_STATUSES.map((status) => (
            <option key={status} value={status}>
              {COLUMN_LABELS[status]}
            </option>
          ))}
        </select>
      </label>
    </article>
  );
}

/** 기한이 없는 것은 다음 예약이 아직 없다는 뜻이다. 빈칸으로 두면 누락으로 읽힌다. */
function dueLabel(task: OpsTask): string {
  if (!task.dueFrom) {
    return '기한 없음';
  }
  const from = task.dueFrom.slice(0, 16).replace('T', ' ');
  return task.dueTo ? `${from} ~ ${task.dueTo.slice(0, 16).replace('T', ' ')}` : `${from} ~ 열림`;
}

function group(tasks: OpsTask[]): Record<TaskStatus, OpsTask[]> {
  const empty = { TODO: [], IN_PROGRESS: [], DONE: [], BLOCKED: [] } as Record<
    TaskStatus,
    OpsTask[]
  >;
  for (const task of tasks) {
    empty[task.status].push(task);
  }
  return empty;
}
