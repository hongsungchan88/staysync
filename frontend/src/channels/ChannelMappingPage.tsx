import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createMapping, deleteMapping, fetchMappingBoard } from '@/api/channels';
import { ApiError } from '@/api/client';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { channelLabel } from '@/calendar/channels';
import { externalIdHint } from './capabilities';

/**
 * 매핑 설정. 계획서 8.1 의 `/channels/:id/mapping` 이다.
 *
 * **매핑되지 않은 판매 단위를 눈에 띄게 표시하는 것이 이 화면의 요점이다.**
 * 매핑이 없으면 그 단위는 이 채널에 나가지 않는데, 목록에서 조용히 빠져 있으면
 * 호스트는 예약이 들어오지 않는 이유를 알 수 없다.
 */
export function ChannelMappingPage() {
  const { connectionId } = useParams();
  const id = Number(connectionId);
  const queryClient = useQueryClient();

  const board = useQuery({
    queryKey: ['channel-mappings', id],
    queryFn: () => fetchMappingBoard(id),
    enabled: Number.isFinite(id),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['channel-mappings', id] });
  const remove = useMutation({
    mutationFn: (mappingId: number) => deleteMapping(id, mappingId),
    onSuccess: invalidate,
  });

  const connection = board.data?.connection;
  const unmappedCount = board.data?.units.filter((unit) => !unit.mapping).length ?? 0;

  return (
    <div className="mx-auto flex h-full w-full max-w-4xl flex-col gap-5 overflow-auto p-6">
      <header className="flex items-baseline justify-between">
        <div className="flex items-baseline gap-3">
          <h1 className="text-lg font-semibold text-ink">채널 매핑</h1>
          {connection && (
            <span className="text-sm text-muted">
              {connection.displayName || channelLabel(connection.channelCode)} ·{' '}
              {connection.adapterType}
            </span>
          )}
        </div>
        <Link to="/channels" className="text-sm text-clay underline-offset-2 hover:underline">
          채널 목록으로
        </Link>
      </header>

      {board.isLoading && <p className="text-sm text-muted">불러오는 중입니다…</p>}
      {board.isError && <p className="text-sm text-warn">채널 연결을 찾을 수 없습니다.</p>}

      {unmappedCount > 0 && (
        <p className="rounded-md border border-rule bg-paper p-3 text-sm text-warn">
          매핑되지 않은 판매 단위가 {unmappedCount}개 있습니다. 이 단위는 이 채널에 나가지
          않습니다.
        </p>
      )}

      <ul className="flex flex-col gap-3">
        {board.data?.units.map((unit) => (
          <li
            key={unit.unitId}
            className={
              unit.mapping
                ? 'rounded-md border border-rule bg-paper p-4'
                : 'rounded-md border border-warn bg-paper p-4'
            }
          >
            <div className="flex items-center justify-between gap-3">
              <div>
                <span className="font-medium text-ink">{unit.unitName}</span>
                {unit.mapping ? (
                  <p className="mt-1 font-mono text-xs text-muted">
                    {unit.mapping.externalUnitId}
                    {unit.mapping.externalRateId ? ` / ${unit.mapping.externalRateId}` : ''}
                  </p>
                ) : (
                  <p className="mt-1 text-xs text-warn">매핑되지 않음</p>
                )}
              </div>
              {unit.mapping && (
                <Button size="sm" onClick={() => remove.mutate(unit.mapping!.id)}>
                  매핑 해제
                </Button>
              )}
            </div>

            {!unit.mapping && connection && (
              <MappingForm
                connectionId={id}
                unitId={unit.unitId}
                hint={externalIdHint(connection.adapterType)}
                onCreated={invalidate}
              />
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

function MappingForm({
  connectionId,
  unitId,
  hint,
  onCreated,
}: {
  connectionId: number;
  unitId: number;
  hint: string;
  onCreated: () => void;
}) {
  const [externalUnitId, setExternalUnitId] = useState('');
  const [externalRateId, setExternalRateId] = useState('');
  const [error, setError] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: () =>
      createMapping(connectionId, {
        unitId,
        externalUnitId,
        externalRateId: externalRateId || undefined,
      }),
    onSuccess: () => {
      setError(null);
      onCreated();
    },
    onError: (e) =>
      setError(
        e instanceof ApiError && e.code === 'DUPLICATE_CHANNEL_MAPPING'
          ? '이 연결에 이미 매핑된 판매 단위입니다.'
          : '매핑하지 못했습니다.',
      ),
  });

  return (
    <form
      className="mt-3 flex items-end gap-2 border-t border-rule pt-3"
      onSubmit={(event) => {
        event.preventDefault();
        create.mutate();
      }}
    >
      <label className="flex-1 text-xs text-muted">
        채널 쪽 식별자
        <Input
          className="mt-1"
          value={externalUnitId}
          onChange={(event) => setExternalUnitId(event.target.value)}
          placeholder={hint}
        />
      </label>
      <label className="flex-1 text-xs text-muted">
        요금 식별자 (선택)
        <Input
          className="mt-1"
          value={externalRateId}
          onChange={(event) => setExternalRateId(event.target.value)}
          placeholder="rate_plan_id"
        />
      </label>
      <Button variant="primary" type="submit" disabled={!externalUnitId || create.isPending}>
        매핑
      </Button>
      {error && <p className="text-xs text-warn">{error}</p>}
    </form>
  );
}
