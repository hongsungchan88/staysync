import { apiRequest } from './client';
import {
  channelConnectionSchema,
  channelMappingSchema,
  mappingBoardSchema,
  type AdapterType,
  type ChannelConnection,
  type ChannelMapping,
  type MappingBoard,
} from './schemas';
import { z } from 'zod';

export async function fetchChannels(): Promise<ChannelConnection[]> {
  const body = await apiRequest<unknown>('/api/channels');
  return z.array(channelConnectionSchema).parse(body);
}

export interface ConnectionInput {
  channelCode: string;
  adapterType: AdapterType;
  displayName?: string;
  /** 키는 어댑터 종류가 정한다. `credentialKey()` 참고. */
  credentials: Record<string, string>;
}

export async function createChannel(
  propertyId: number,
  input: ConnectionInput,
): Promise<ChannelConnection> {
  const body = await apiRequest<unknown>(`/api/properties/${propertyId}/channels`, {
    method: 'POST',
    body: input,
  });
  return channelConnectionSchema.parse(body);
}

/**
 * 연결 수정.
 *
 * **자격 증명을 비워 보내면 서버가 기존 값을 유지한다.** 화면은 저장된 값을 다시 받지
 * 못하므로(마스킹된 형태만 본다) 표시 이름만 고치는 경우가 이 경로로 온다. 빈 값을
 * 그대로 반영하면 연결이 조용히 끊긴다.
 */
export async function updateChannel(
  connectionId: number,
  input: { displayName?: string; syncEnabled?: boolean; credentials?: Record<string, string> },
): Promise<ChannelConnection> {
  const body = await apiRequest<unknown>(`/api/channels/${connectionId}`, {
    method: 'PATCH',
    body: input,
  });
  return channelConnectionSchema.parse(body);
}

export async function deleteChannel(connectionId: number): Promise<void> {
  await apiRequest<void>(`/api/channels/${connectionId}`, { method: 'DELETE' });
}

export async function fetchMappingBoard(connectionId: number): Promise<MappingBoard> {
  const body = await apiRequest<unknown>(`/api/channels/${connectionId}/mappings`);
  return mappingBoardSchema.parse(body);
}

export async function createMapping(
  connectionId: number,
  input: { unitId: number; externalUnitId: string; externalRateId?: string },
): Promise<ChannelMapping> {
  const body = await apiRequest<unknown>(`/api/channels/${connectionId}/mappings`, {
    method: 'POST',
    body: input,
  });
  return channelMappingSchema.parse(body);
}

/**
 * iCal 발행 URL.
 *
 * **토큰이 실리는 유일한 경로다.** 매핑 목록에는 담기지 않는다 — 그 응답은 화면을
 * 그릴 때마다 오가서 브라우저 캐시와 프록시 로그 어디에나 남고, 새는 쪽에서는 아무
 * 증상이 없다. 호스트가 에어비앤비에 붙여 넣을 때만 눌러서 받는다.
 */
export async function fetchExportUrl(connectionId: number, mappingId: number): Promise<string> {
  const body = await apiRequest<unknown>(
    `/api/channels/${connectionId}/mappings/${mappingId}/export-url`,
  );
  return z.object({ url: z.string() }).parse(body).url;
}

export async function deleteMapping(connectionId: number, mappingId: number): Promise<void> {
  await apiRequest<void>(`/api/channels/${connectionId}/mappings/${mappingId}`, {
    method: 'DELETE',
  });
}
