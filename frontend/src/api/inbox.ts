import { z } from 'zod';
import { apiRequest } from './client';
import {
  messageSchema,
  messageTemplateSchema,
  threadSchema,
  threadSummarySchema,
  type InboxMessage,
  type InboxThread,
  type MessageTemplate,
  type ThreadSummary,
} from './schemas';

export async function fetchThreads(): Promise<ThreadSummary[]> {
  const body = await apiRequest<unknown>('/api/inbox');
  return z.array(threadSummarySchema).parse(body);
}

/** **여는 것이 곧 읽음 처리다.** 서버가 미읽음을 0으로 만든다. */
export async function fetchThread(threadId: number): Promise<InboxThread> {
  const body = await apiRequest<unknown>(`/api/inbox/${threadId}`);
  return threadSchema.parse(body);
}

/**
 * 메시지를 보낸다.
 *
 * **되돌릴 수 없다.** `templateId` 를 넘기면 서버가 치환하고, 채울 수 없는 변수가
 * 있으면 422 로 막는다 — 치환되지 않은 변수가 그대로 나가면 안 된다.
 */
export async function sendMessage(
  threadId: number,
  input: { body?: string; templateId?: number },
): Promise<InboxMessage> {
  const body = await apiRequest<unknown>(`/api/inbox/${threadId}/messages`, {
    method: 'POST',
    body: input,
  });
  return messageSchema.parse(body);
}

export async function fetchTemplates(): Promise<MessageTemplate[]> {
  const body = await apiRequest<unknown>('/api/message-templates');
  return z.array(messageTemplateSchema).parse(body);
}
