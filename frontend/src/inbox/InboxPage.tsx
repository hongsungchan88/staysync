import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchThread, fetchThreads, fetchTemplates, sendMessage } from '@/api/inbox';
import { ApiError } from '@/api/client';
import type { InboxMessage, ThreadSummary } from '@/api/schemas';
import { Button } from '@/components/ui/button';
import { channelLabel } from '@/calendar/channels';

/**
 * 통합 인박스. 계획서 8.5 이고 경로는 `/inbox`, `/inbox/:threadId` 다.
 *
 * **이 화면의 요점은 무엇을 보낼 수 없는지를 먼저 보여 주는 것이다.** iCal 스레드에는
 * 입력창이 없다. 채널이 메시징을 지원하지 않는다는 사실을 호스트가 답을 쓴 뒤가
 * 아니라 대화를 열 때 알아야 한다. 계획서 6.1 의 `capabilities()` 가 화면에서 실제로
 * 무언가를 바꾸는 세 번째 자리다.
 *
 * 실시간 갱신을 붙이지 않았다. 읽음 표시는 새로고침으로 맞춘다(작업지시 11 의 3절).
 */
export function InboxPage() {
  const { threadId } = useParams();
  const selected = threadId ? Number(threadId) : undefined;
  const navigate = useNavigate();

  const [sortByCheckIn, setSortByCheckIn] = useState(false);
  const threads = useQuery({ queryKey: ['inbox'], queryFn: fetchThreads });

  // 계획서 8.5 가 "체크인 임박 순 정렬"을 적었는데 기본은 최근 대화 순이다.
  // 하나로 고정하면 답을 기다리는 대화가 체크인이 먼 순서에 묻힌다.
  const sorted = useMemo(() => {
    const list = [...(threads.data ?? [])];
    if (!sortByCheckIn) {
      return list;
    }
    return list.sort((a, b) => (a.checkIn ?? '9999').localeCompare(b.checkIn ?? '9999'));
  }, [threads.data, sortByCheckIn]);

  return (
    <div className="mx-auto flex h-full w-full max-w-5xl flex-col gap-4 overflow-hidden p-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-lg font-semibold text-ink">인박스</h1>
        <Link to="/" className="text-sm text-clay underline-offset-2 hover:underline">
          캘린더로
        </Link>
      </header>

      <div className="flex min-h-0 flex-1 gap-4">
        <aside className="flex w-72 shrink-0 flex-col gap-2 overflow-auto">
          <label className="flex items-center gap-2 text-xs text-muted">
            <input
              type="checkbox"
              checked={sortByCheckIn}
              onChange={(event) => setSortByCheckIn(event.target.checked)}
            />
            체크인 임박 순
          </label>

          {threads.isLoading && <p className="text-sm text-muted">불러오는 중입니다…</p>}
          {sorted.length === 0 && !threads.isLoading && (
            <p className="rounded-md border border-rule bg-paper p-3 text-sm text-muted">
              아직 대화가 없습니다.
            </p>
          )}

          {sorted.map((thread) => (
            <ThreadRow
              key={thread.id}
              thread={thread}
              selected={thread.id === selected}
              onSelect={() => navigate(`/inbox/${thread.id}`)}
            />
          ))}
        </aside>

        <section className="min-w-0 flex-1 overflow-auto rounded-md border border-rule bg-paper">
          {selected ? (
            <Conversation threadId={selected} />
          ) : (
            <p className="p-6 text-sm text-muted">왼쪽에서 대화를 고르세요.</p>
          )}
        </section>
      </div>
    </div>
  );
}

function ThreadRow({
  thread,
  selected,
  onSelect,
}: {
  thread: ThreadSummary;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-current={selected ? 'true' : undefined}
      className={
        selected
          ? 'rounded-md border border-clay bg-paper p-3 text-left'
          : 'rounded-md border border-rule bg-paper p-3 text-left hover:bg-faint'
      }
    >
      <span className="flex items-baseline justify-between gap-2">
        <span className="truncate text-sm font-medium text-ink">
          {thread.guestName ?? thread.confirmationCode ?? '(예약 미연결)'}
        </span>
        {thread.unreadCount > 0 && (
          <span className="shrink-0 rounded-full bg-clay px-2 text-xs text-paper">
            {thread.unreadCount}
          </span>
        )}
      </span>
      <span className="mt-1 block text-xs text-muted">{channelLabel(thread.channelCode)}</span>
      {thread.checkIn && (
        <span className="mt-1 block text-xs text-muted">
          {thread.checkIn} ~ {thread.checkOut}
        </span>
      )}
    </button>
  );
}

function Conversation({ threadId }: { threadId: number }) {
  const queryClient = useQueryClient();
  const [body, setBody] = useState('');
  const [templateId, setTemplateId] = useState<number | undefined>();
  const [error, setError] = useState<string | null>(null);

  const thread = useQuery({ queryKey: ['inbox', threadId], queryFn: () => fetchThread(threadId) });
  const templates = useQuery({ queryKey: ['message-templates'], queryFn: fetchTemplates });

  const send = useMutation({
    mutationFn: () => sendMessage(threadId, { body: body || undefined, templateId }),
    onSuccess: () => {
      setError(null);
      setBody('');
      setTemplateId(undefined);
      queryClient.invalidateQueries({ queryKey: ['inbox'] });
    },
    onError: (e) =>
      setError(
        e instanceof ApiError
          ? // 치환할 값이 없으면 422 다. 어느 변수가 비었는지 서버가 알려 준다.
            e.message
          : '보내지 못했습니다. 잠시 뒤 다시 시도하세요.',
      ),
  });

  if (thread.isLoading) {
    return <p className="p-6 text-sm text-muted">불러오는 중입니다…</p>;
  }
  if (thread.isError || !thread.data) {
    return <p className="p-6 text-sm text-warn">대화를 찾을 수 없습니다.</p>;
  }

  const summary = thread.data.thread;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <header className="border-b border-rule p-4">
        <p className="text-sm font-medium text-ink">
          {summary.guestName ?? summary.confirmationCode ?? '(예약 미연결)'}
        </p>
        <p className="mt-1 text-xs text-muted">
          {channelLabel(summary.channelCode)}
          {summary.checkIn && ` · ${summary.checkIn} ~ ${summary.checkOut}`}
          {summary.reservationStatus && ` · ${summary.reservationStatus}`}
        </p>
      </header>

      <ul className="flex min-h-0 flex-1 flex-col gap-2 overflow-auto p-4">
        {thread.data.messages.map((message) => (
          <MessageBubble key={message.id} message={message} />
        ))}
        {thread.data.messages.length === 0 && (
          <li className="text-sm text-muted">아직 주고받은 메시지가 없습니다.</li>
        )}
      </ul>

      {thread.data.messagingSupported ? (
        <form
          className="flex flex-col gap-2 border-t border-rule p-4"
          onSubmit={(event) => {
            event.preventDefault();
            send.mutate();
          }}
        >
          <select
            className="rounded-md border border-rule bg-paper p-2 text-sm text-ink"
            value={templateId ?? ''}
            onChange={(event) => setTemplateId(Number(event.target.value) || undefined)}
            aria-label="템플릿"
          >
            <option value="">직접 입력</option>
            {templates.data?.map((template) => (
              <option key={template.id} value={template.id}>
                {template.name}
              </option>
            ))}
          </select>

          {!templateId && (
            <textarea
              className="min-h-20 rounded-md border border-rule bg-paper p-2 text-sm text-ink"
              value={body}
              onChange={(event) => setBody(event.target.value)}
              placeholder="답장을 쓰세요"
              aria-label="답장"
            />
          )}

          {error && <p className="text-xs text-warn">{error}</p>}

          <Button
            variant="primary"
            type="submit"
            disabled={send.isPending || (!templateId && !body.trim())}
          >
            보내기
          </Button>
        </form>
      ) : (
        // 계획서 6.1 의 요점이 화면에 드러나는 자리다. 입력창을 두고 실패를 보여 주면
        // 호스트는 답을 다 쓴 뒤에야 보낼 수 없다는 것을 안다.
        <p className="border-t border-rule p-4 text-sm text-warn">
          {channelLabel(summary.channelCode)} 는 메시지 발송을 지원하지 않습니다. 이 채널의
          대화에는 답장할 수 없습니다.
        </p>
      )}
    </div>
  );
}

/** 발신자를 구분해 그린다. `SYSTEM` 은 사람이 쓰지 않았다는 사실이 드러나야 한다. */
function MessageBubble({ message }: { message: InboxMessage }) {
  const guest = message.direction === 'INBOUND';
  return (
    <li className={guest ? 'flex justify-start' : 'flex justify-end'}>
      <span
        className={
          guest
            ? 'max-w-[80%] rounded-md border border-rule bg-faint p-2 text-sm text-ink'
            : 'max-w-[80%] rounded-md border border-clay bg-paper p-2 text-sm text-ink'
        }
      >
        <span className="mb-1 block text-xs text-muted">
          {message.sender === 'SYSTEM' ? '자동 발송' : message.sender === 'GUEST' ? '게스트' : '호스트'}
          {' · '}
          {message.sentAt.slice(0, 16).replace('T', ' ')}
        </span>
        {message.body}
      </span>
    </li>
  );
}
