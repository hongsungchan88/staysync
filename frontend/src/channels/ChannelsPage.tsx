import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createChannel,
  deleteChannel,
  fetchChannels,
  updateChannel,
  type ConnectionInput,
} from '@/api/channels';
import { fetchProperties } from '@/api/calendar';
import { ApiError } from '@/api/client';
import type { AdapterType, ChannelConnection } from '@/api/schemas';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { channelLabel } from '@/calendar/channels';
import {
  SHOWN_CAPABILITIES,
  capabilityLabel,
  credentialKey,
  credentialLabel,
} from './capabilities';

const ADAPTER_TYPES: AdapterType[] = ['ICAL', 'CHANNEX', 'MOCK'];

/**
 * 채널 연결 목록. 계획서 8.1 의 `/channels` 다.
 *
 * 연결마다 지원 기능을 함께 보여 준다. 어댑터 구현은 11~13주차에 들어오지만, 지원
 * 기능은 채널 종류가 정하는 것이라 지금도 옳게 보여줄 수 있다.
 */
export function ChannelsPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<number | null>(null);

  const properties = useQuery({ queryKey: ['properties'], queryFn: fetchProperties });
  const channels = useQuery({ queryKey: ['channels'], queryFn: fetchChannels });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['channels'] });

  const remove = useMutation({ mutationFn: deleteChannel, onSuccess: invalidate });

  return (
    <div className="mx-auto flex h-full w-full max-w-4xl flex-col gap-5 overflow-auto p-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-lg font-semibold text-ink">채널 연결</h1>
        <Link to="/" className="text-sm text-clay underline-offset-2 hover:underline">
          캘린더로
        </Link>
      </header>

      {channels.isLoading && <p className="text-sm text-muted">불러오는 중입니다…</p>}

      {channels.data?.length === 0 && (
        <p className="rounded-md border border-rule bg-paper p-4 text-sm text-muted">
          연결된 채널이 없습니다. 아래에서 추가하세요.
        </p>
      )}

      <ul className="flex flex-col gap-3">
        {channels.data?.map((connection) => (
          <li key={connection.id} className="rounded-md border border-rule bg-paper p-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <div className="flex items-center gap-2">
                  <span className="font-medium text-ink">
                    {connection.displayName || channelLabel(connection.channelCode)}
                  </span>
                  <span className="rounded bg-faint px-1.5 py-0.5 text-xs text-muted">
                    {connection.adapterType}
                  </span>
                  {!connection.syncEnabled && (
                    <span className="rounded bg-faint px-1.5 py-0.5 text-xs text-warn">중지됨</span>
                  )}
                </div>
                <p className="mt-1 text-xs text-muted">
                  {connection.channelCode} · 자격 증명{' '}
                  {Object.entries(connection.credentials).map(([key, masked]) => (
                    <span key={key} className="font-mono">
                      {key}={masked}{' '}
                    </span>
                  ))}
                  {Object.keys(connection.credentials).length === 0 && '없음'}
                </p>
                <CapabilityBadges capabilities={connection.capabilities} />
              </div>
              <div className="flex shrink-0 gap-2">
                <Link
                  to={`/channels/${connection.id}/mapping`}
                  className="inline-flex h-8 items-center rounded-md border border-rule-strong px-3 text-xs text-body hover:bg-faint"
                >
                  매핑
                </Link>
                <Button
                  size="sm"
                  onClick={() => setEditing(editing === connection.id ? null : connection.id)}
                >
                  수정
                </Button>
                <Button
                  size="sm"
                  onClick={() => remove.mutate(connection.id)}
                  disabled={remove.isPending}
                >
                  삭제
                </Button>
              </div>
            </div>

            {editing === connection.id && (
              <EditForm
                connection={connection}
                onDone={() => {
                  setEditing(null);
                  void invalidate();
                }}
              />
            )}
          </li>
        ))}
      </ul>

      <CreateForm
        properties={properties.data ?? []}
        onCreated={invalidate}
      />
    </div>
  );
}

/**
 * 지원 기능 표시.
 *
 * 지원하지 않는 것을 지우지 않고 **"미지원"으로 남긴다.** 없는 항목은 눈에 띄지 않아서
 * 호스트가 "요금이 왜 안 넘어가지"를 뒤늦게 겪는다.
 */
function CapabilityBadges({ capabilities }: { capabilities: string[] }) {
  return (
    <ul className="mt-2 flex flex-wrap gap-1.5">
      {SHOWN_CAPABILITIES.map((code) => {
        const supported = capabilities.includes(code);
        return (
          <li
            key={code}
            className={
              supported
                ? 'rounded border border-rule px-1.5 py-0.5 text-xs text-body'
                : 'rounded border border-rule px-1.5 py-0.5 text-xs text-warn'
            }
          >
            {capabilityLabel(code)} {supported ? '지원' : '미지원'}
          </li>
        );
      })}
    </ul>
  );
}

function CreateForm({
  properties,
  onCreated,
}: {
  properties: { id: number; name: string }[];
  onCreated: () => void;
}) {
  const [propertyId, setPropertyId] = useState<number | null>(null);
  const [channelCode, setChannelCode] = useState('AIRBNB_ICAL');
  const [adapterType, setAdapterType] = useState<AdapterType>('ICAL');
  const [displayName, setDisplayName] = useState('');
  const [secret, setSecret] = useState('');
  const [error, setError] = useState<string | null>(null);

  const target = propertyId ?? properties[0]?.id ?? null;

  const create = useMutation({
    mutationFn: (input: ConnectionInput) => createChannel(target!, input),
    onSuccess: () => {
      setSecret('');
      setDisplayName('');
      setError(null);
      onCreated();
    },
    onError: (e) =>
      setError(
        e instanceof ApiError && e.code === 'DUPLICATE_CHANNEL_CONNECTION'
          ? '이 숙소에 같은 채널이 이미 있습니다.'
          : '연결을 만들지 못했습니다.',
      ),
  });

  return (
    <form
      className="rounded-md border border-rule bg-paper p-4"
      onSubmit={(event) => {
        event.preventDefault();
        if (target === null) {
          setError('숙소를 먼저 등록하세요.');
          return;
        }
        create.mutate({
          channelCode,
          adapterType,
          displayName: displayName || undefined,
          credentials: { [credentialKey(adapterType)]: secret },
        });
      }}
    >
      <h2 className="mb-3 font-medium text-ink">연결 추가</h2>
      <div className="grid grid-cols-2 gap-3">
        <label className="text-xs text-muted">
          숙소
          <select
            className="mt-1 h-9 w-full rounded-md border border-rule-strong bg-paper px-2 text-sm text-ink"
            value={target ?? ''}
            onChange={(event) => setPropertyId(Number(event.target.value))}
          >
            {properties.map((property) => (
              <option key={property.id} value={property.id}>
                {property.name}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs text-muted">
          어댑터 종류
          <select
            className="mt-1 h-9 w-full rounded-md border border-rule-strong bg-paper px-2 text-sm text-ink"
            value={adapterType}
            onChange={(event) => setAdapterType(event.target.value as AdapterType)}
          >
            {ADAPTER_TYPES.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs text-muted">
          채널 코드
          <Input
            className="mt-1"
            value={channelCode}
            onChange={(event) => setChannelCode(event.target.value)}
            placeholder="AIRBNB_ICAL"
          />
        </label>
        <label className="text-xs text-muted">
          표시 이름
          <Input
            className="mt-1"
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            placeholder="선택"
          />
        </label>
        <label className="col-span-2 text-xs text-muted">
          {credentialLabel(adapterType)}
          <Input
            className="mt-1"
            type="password"
            value={secret}
            onChange={(event) => setSecret(event.target.value)}
            placeholder="저장하면 마스킹된 형태로만 보입니다"
          />
        </label>
      </div>
      {error && <p className="mt-2 text-xs text-warn">{error}</p>}
      <Button className="mt-3" variant="primary" type="submit" disabled={create.isPending}>
        추가
      </Button>
    </form>
  );
}

/**
 * 수정 폼.
 *
 * **자격 증명 칸은 비어 있는 채로 시작한다.** 저장된 값을 받아오지 않기 때문이다.
 * 비운 채로 저장하면 서버가 기존 값을 유지한다.
 */
function EditForm({
  connection,
  onDone,
}: {
  connection: ChannelConnection;
  onDone: () => void;
}) {
  const [displayName, setDisplayName] = useState(connection.displayName ?? '');
  const [syncEnabled, setSyncEnabled] = useState(connection.syncEnabled);
  const [secret, setSecret] = useState('');

  const update = useMutation({
    mutationFn: () =>
      updateChannel(connection.id, {
        displayName,
        syncEnabled,
        credentials: { [credentialKey(connection.adapterType)]: secret },
      }),
    onSuccess: onDone,
  });

  return (
    <div className="mt-3 border-t border-rule pt-3">
      <div className="grid grid-cols-2 gap-3">
        <label className="text-xs text-muted">
          표시 이름
          <Input
            className="mt-1"
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
          />
        </label>
        <label className="text-xs text-muted">
          {credentialLabel(connection.adapterType)} (비우면 그대로 유지)
          <Input
            className="mt-1"
            type="password"
            value={secret}
            onChange={(event) => setSecret(event.target.value)}
            placeholder="변경할 때만 입력"
          />
        </label>
      </div>
      <label className="mt-3 flex items-center gap-2 text-xs text-body">
        <input
          type="checkbox"
          checked={syncEnabled}
          onChange={(event) => setSyncEnabled(event.target.checked)}
        />
        동기화 활성
      </label>
      <Button
        className="mt-3"
        variant="primary"
        size="sm"
        onClick={() => update.mutate()}
        disabled={update.isPending}
      >
        저장
      </Button>
    </div>
  );
}
