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

// MOCK 은 시뮬레이터 상대역이라 운영 빌드에서는 고를 수 없게 한다(작업지시-20 D).
const ADAPTER_TYPES: AdapterType[] = import.meta.env.PROD
  ? ['ICAL', 'CHANNEX']
  : ['ICAL', 'CHANNEX', 'MOCK'];

/**
 * 채널 연결 목록. 계획서 8.1 의 `/channels` 다.
 *
 * 연결마다 지원 기능을 함께 보여 준다. 어댑터 구현은 11~13주차에 들어오지만, 지원
 * 기능은 채널 종류가 정하는 것이라 지금도 옳게 보여줄 수 있다.
 */
export function ChannelsPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<number | null>(null);
  // 삭제는 되돌릴 수 없다 — 저장된 주소·키를 화면이 다시 보여 주지 못한다. 한 번 더 묻는다.
  const [confirming, setConfirming] = useState<number | null>(null);

  const properties = useQuery({ queryKey: ['properties'], queryFn: fetchProperties });
  const channels = useQuery({ queryKey: ['channels'], queryFn: fetchChannels });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['channels'] });

  const remove = useMutation({
    mutationFn: deleteChannel,
    onSuccess: () => {
      setConfirming(null);
      return invalidate();
    },
  });

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
                {connection.adapterType === 'ICAL' && (
                  // 미지원 셋이 먼저 보이면 고장난 연결로 읽힌다. iCal 은 원래 받기만 한다.
                  <p className="mt-2 text-xs text-body">
                    iCal 은 예약을 받아 오기만 하는 연결입니다. 재고·요금·제약은 이 연결로 보내지
                    않습니다.
                  </p>
                )}
                <CapabilityBadges capabilities={connection.capabilities} />
                {connection.deadJobs > 0 && (
                  // 조용히 실패하는 자리를 남기지 않는다(작업지시-17 8.3). 재시도가 포기한 전송은
                  // 채널이 값을 거부했거나 매핑이 틀린 것이라 사람이 고쳐야 한다.
                  <p className="mt-2 text-xs text-warn" role="alert">
                    실패한 전송 {connection.deadJobs}건
                    {connection.lastError ? ` · 마지막 오류: ${connection.lastError}` : ''}
                  </p>
                )}
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
                  onClick={() => {
                    remove.reset();
                    setConfirming(confirming === connection.id ? null : connection.id);
                  }}
                  aria-expanded={confirming === connection.id}
                >
                  삭제
                </Button>
              </div>
            </div>

            {confirming === connection.id && (
              <div
                role="alertdialog"
                aria-label="연결 삭제 확인"
                className="mt-3 rounded-md border border-warn p-3 text-xs text-body"
                data-testid={`delete-confirm-${connection.id}`}
              >
                <p>
                  이 연결을 삭제하면 매핑도 함께 지워지고 이 연결로 들어오던 예약 수신이 끊깁니다.
                  이미 받은 예약은 캘린더에 남습니다.
                </p>
                <p className="mt-1 font-medium text-warn">
                  저장된 {credentialLabel(connection.adapterType)}는 보안상 화면에 다시 보여 드릴 수
                  없습니다. 다시 연결하려면{' '}
                  {connection.adapterType === 'CHANNEX' ? 'Channex' : channelLabel(connection.channelCode)}에서 주소나 키를 새로
                  받아 입력하고 매핑도 다시 해야 합니다.
                </p>
                {remove.isError && (
                  <p className="mt-1 text-warn" role="alert">
                    삭제하지 못했습니다.
                  </p>
                )}
                <div className="mt-2 flex gap-2">
                  <Button
                    size="sm"
                    variant="primary"
                    onClick={() => remove.mutate(connection.id)}
                    disabled={remove.isPending}
                    data-testid={`delete-confirm-button-${connection.id}`}
                  >
                    삭제합니다
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setConfirming(null)}>
                    취소
                  </Button>
                </div>
              </div>
            )}

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
  // Channex 만 둘째 값이 있다 — Channex 쪽 숙소 식별자. 비밀은 아니지만 연결의 일부라
  // 자격 증명과 같은 자리에 저장된다(작업지시-17 A).
  const [channexPropertyId, setChannexPropertyId] = useState('');
  const [error, setError] = useState<string | null>(null);

  const target = propertyId ?? properties[0]?.id ?? null;

  const create = useMutation({
    mutationFn: (input: ConnectionInput) => createChannel(target!, input),
    onSuccess: () => {
      setSecret('');
      setChannexPropertyId('');
      setDisplayName('');
      setError(null);
      onCreated();
    },
    onError: (e) =>
      setError(
        e instanceof ApiError && e.code === 'DUPLICATE_CHANNEL_CONNECTION'
          ? '이 숙소에 같은 채널이 이미 있습니다.'
          : e instanceof ApiError && e.code === 'CHANNEL_FIELD_MISSING'
            ? e.message
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
          credentials:
            adapterType === 'CHANNEX'
              ? { [credentialKey(adapterType)]: secret, property_id: channexPropertyId }
              : { [credentialKey(adapterType)]: secret },
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
        {adapterType === 'CHANNEX' && (
          <label className="col-span-2 text-xs text-muted">
            Channex 숙소 식별자 (property_id)
            <Input
              className="mt-1"
              value={channexPropertyId}
              onChange={(event) => setChannexPropertyId(event.target.value)}
              placeholder="Channex 콘솔의 숙소 UUID"
            />
          </label>
        )}
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
