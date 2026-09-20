import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchProperties } from '@/api/calendar';
import { ApiError } from '@/api/client';
import {
  UNIT_KIND_LABEL,
  changeUnitCapacity,
  createProperty,
  createUnit,
  fetchUnits,
  renameUnit,
  updateProperty,
  type Unit,
  type UnitKind,
} from '@/api/properties';
import type { PropertySummary } from '@/api/schemas';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

/**
 * 숙소·판매 단위 등록·편집(작업지시-19 B). `/properties`.
 *
 * 호스트가 혼자 시작하는 자리다 — 가입 직후 숙소가 없으면 캘린더가 여기로 보낸다.
 * 서버는 P1 3주차의 API 그대로이고 화면만 새로 붙었다.
 *
 * **요금제는 드러내지 않는다.** 판매 단위를 만들면 기본 요금제가 함께 생기지만 채널이
 * 요구해서 데이터로만 두는 것이다(5절 2번). 날짜별 요금·최소 숙박은 캘린더의 기간 선택
 * 편집이 한다.
 *
 * **수량 변경은 다른 경로다**(C). 이미 팔린 날 아래로 줄이면 서버가 409 와 막는 날짜들을
 * 주고, 화면은 그 날짜들을 그대로 보여 준다 — 어느 예약을 옮겨야 줄일 수 있는지 사람이
 * 알아야 다음 행동을 고른다. 강행하는 길은 없다.
 */
export function PropertiesPage() {
  const properties = useQuery({ queryKey: ['properties'], queryFn: fetchProperties });

  return (
    <div className="mx-auto flex h-full w-full max-w-3xl flex-col gap-5 overflow-auto p-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-lg font-semibold text-ink">숙소와 판매 단위</h1>
        <nav className="flex gap-3 text-sm">
          <Link to="/" className="text-clay underline-offset-2 hover:underline">
            캘린더로
          </Link>
          <Link to="/channels" className="text-clay underline-offset-2 hover:underline">
            채널 연결
          </Link>
        </nav>
      </header>

      {properties.isLoading && <p className="text-sm text-muted">불러오는 중입니다…</p>}
      {properties.isError && (
        <p role="alert" className="rounded-md border border-clay bg-paper p-3 text-sm text-clay">
          숙소 목록을 불러오지 못했습니다.
        </p>
      )}

      {properties.data && properties.data.length === 0 && (
        <p
          className="rounded-md border border-rule bg-sand/40 p-3 text-sm text-muted"
          data-testid="properties-empty"
        >
          아직 숙소가 없습니다. 아래에서 숙소를 먼저 등록하세요. 그다음 판매 단위(독채·방·침대)를
          하나 넣으면 캘린더가 나타납니다.
        </p>
      )}

      {properties.data?.map((property) => (
        <PropertyCard key={property.id} property={property} />
      ))}

      <NewPropertyForm />
    </div>
  );
}

// --- 숙소 ------------------------------------------------------------------

function NewPropertyForm() {
  const queryClient = useQueryClient();
  const [name, setName] = useState('');
  const [address, setAddress] = useState('');
  const create = useMutation({
    mutationFn: () => createProperty({ name, address: address || undefined }),
    onSuccess: () => {
      setName('');
      setAddress('');
      void queryClient.invalidateQueries({ queryKey: ['properties'] });
    },
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }

  return (
    <form
      onSubmit={submit}
      aria-label="숙소 추가"
      className="flex flex-col gap-2 rounded-md border border-rule bg-paper p-4"
    >
      <h2 className="text-sm font-semibold text-ink">숙소 추가</h2>
      <label className="text-xs text-muted">
        이름
        <Input
          className="mt-1"
          aria-label="숙소 이름"
          value={name}
          maxLength={200}
          onChange={(e) => setName(e.target.value)}
          required
        />
      </label>
      <label className="text-xs text-muted">
        주소 (선택)
        <Input
          className="mt-1"
          aria-label="숙소 주소"
          value={address}
          maxLength={500}
          onChange={(e) => setAddress(e.target.value)}
        />
      </label>
      <ErrorLine error={create.error} />
      <Button type="submit" variant="primary" size="sm" disabled={create.isPending}>
        숙소 추가
      </Button>
    </form>
  );
}

function PropertyCard({ property }: { property: PropertySummary }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(property.name);
  const [address, setAddress] = useState(property.address ?? '');
  const [checkIn, setCheckIn] = useState(property.checkInTime.slice(0, 5));
  const [checkOut, setCheckOut] = useState(property.checkOutTime.slice(0, 5));

  const save = useMutation({
    // 체크인·체크아웃은 함께 보낸다. 서버가 둘 다 있을 때만 바꾼다 — 하나만 바꾸면
    // 체크인이 체크아웃보다 늦는 조합이 생긴다.
    mutationFn: () =>
      updateProperty(property.id, {
        name,
        address: address || undefined,
        checkInTime: checkIn,
        checkOutTime: checkOut,
      }),
    onSuccess: () => {
      setEditing(false);
      void queryClient.invalidateQueries({ queryKey: ['properties'] });
    },
  });

  return (
    <section
      aria-label={`숙소 ${property.name}`}
      className="flex flex-col gap-3 rounded-md border border-rule bg-paper p-4"
    >
      {editing ? (
        <form
          aria-label="숙소 편집"
          className="flex flex-col gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
        >
          <Input aria-label="이름" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} required />
          <Input aria-label="주소" value={address} maxLength={500} onChange={(e) => setAddress(e.target.value)} />
          <div className="flex gap-2">
            <label className="text-xs text-muted">
              체크인
              <Input className="mt-1" type="time" aria-label="체크인 시각" value={checkIn} onChange={(e) => setCheckIn(e.target.value)} required />
            </label>
            <label className="text-xs text-muted">
              체크아웃
              <Input className="mt-1" type="time" aria-label="체크아웃 시각" value={checkOut} onChange={(e) => setCheckOut(e.target.value)} required />
            </label>
          </div>
          <ErrorLine error={save.error} />
          <div className="flex gap-2">
            <Button type="submit" variant="primary" size="sm" disabled={save.isPending}>
              저장
            </Button>
            <Button type="button" size="sm" onClick={() => setEditing(false)}>
              취소
            </Button>
          </div>
        </form>
      ) : (
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="text-base font-semibold text-ink">{property.name}</h2>
            <p className="text-xs text-muted">
              {property.address ?? '주소 없음'} · 체크인 {property.checkInTime.slice(0, 5)} · 체크아웃{' '}
              {property.checkOutTime.slice(0, 5)}
            </p>
          </div>
          <Button size="sm" onClick={() => setEditing(true)}>
            편집
          </Button>
        </div>
      )}

      <UnitList propertyId={property.id} />
    </section>
  );
}

// --- 판매 단위 --------------------------------------------------------------

function UnitList({ propertyId }: { propertyId: number }) {
  const units = useQuery({
    queryKey: ['units', propertyId],
    queryFn: () => fetchUnits(propertyId),
  });

  return (
    <div className="flex flex-col gap-2 border-t border-rule pt-3">
      <h3 className="text-xs font-semibold text-muted">판매 단위</h3>
      {units.data?.length === 0 && (
        <p className="text-xs text-muted">아직 판매 단위가 없습니다. 하나 넣으면 캘린더에 줄이 생깁니다.</p>
      )}
      <ul className="flex flex-col gap-2">
        {units.data?.map((unit) => (
          <UnitRow key={unit.id} unit={unit} />
        ))}
      </ul>
      <NewUnitForm propertyId={propertyId} />
    </div>
  );
}

function NewUnitForm({ propertyId }: { propertyId: number }) {
  const queryClient = useQueryClient();
  const [name, setName] = useState('');
  const [unitKind, setUnitKind] = useState<UnitKind>('ENTIRE_PLACE');
  const [totalUnits, setTotalUnits] = useState('1');
  const [basePrice, setBasePrice] = useState('');
  const create = useMutation({
    mutationFn: () =>
      createUnit(propertyId, {
        name,
        unitKind,
        totalUnits: Number(totalUnits),
        basePrice: Number(basePrice),
      }),
    onSuccess: () => {
      setName('');
      setBasePrice('');
      void queryClient.invalidateQueries({ queryKey: ['units', propertyId] });
      // 캘린더는 판매 단위 수로 빈 상태를 판단한다. 다음에 열 때 새로 읽게 한다.
      void queryClient.invalidateQueries({ queryKey: ['calendar'] });
    },
  });

  return (
    <form
      aria-label="판매 단위 추가"
      className="flex flex-wrap items-end gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        create.mutate();
      }}
    >
      <label className="text-xs text-muted">
        이름
        <Input className="mt-1 w-32" aria-label="판매 단위 이름" value={name} maxLength={200} onChange={(e) => setName(e.target.value)} required />
      </label>
      <label className="text-xs text-muted">
        종류
        <select
          className="mt-1 h-9 rounded-md border border-rule-strong bg-paper px-2 text-sm text-ink"
          aria-label="종류"
          value={unitKind}
          onChange={(e) => setUnitKind(e.target.value as UnitKind)}
        >
          {(Object.keys(UNIT_KIND_LABEL) as UnitKind[]).map((kind) => (
            <option key={kind} value={kind}>
              {UNIT_KIND_LABEL[kind]}
            </option>
          ))}
        </select>
      </label>
      <label className="text-xs text-muted">
        수량
        <Input className="mt-1 w-16" type="number" min={1} max={999} aria-label="수량" value={totalUnits} onChange={(e) => setTotalUnits(e.target.value)} required />
      </label>
      <label className="text-xs text-muted">
        기본 요금(원)
        <Input className="mt-1 w-28" type="number" min={0} step={1000} aria-label="기본 요금" value={basePrice} onChange={(e) => setBasePrice(e.target.value)} required />
      </label>
      <Button type="submit" variant="primary" size="sm" disabled={create.isPending}>
        추가
      </Button>
      <ErrorLine error={create.error} />
    </form>
  );
}

function UnitRow({ unit }: { unit: Unit }) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(unit.name);
  const [totalUnits, setTotalUnits] = useState(String(unit.totalUnits));
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['units', unit.propertyId] });
    void queryClient.invalidateQueries({ queryKey: ['calendar'] });
  };
  const rename = useMutation({ mutationFn: () => renameUnit(unit.id, name), onSuccess: refresh });
  const capacity = useMutation({
    mutationFn: () => changeUnitCapacity(unit.id, Number(totalUnits)),
    onSuccess: refresh,
  });

  const dirtyName = name !== unit.name;
  const dirtyCapacity = Number(totalUnits) !== unit.totalUnits;

  return (
    <li
      className="flex flex-wrap items-end gap-2 rounded-md border border-rule/60 p-2"
      aria-label={`판매 단위 ${unit.name}`}
    >
      <span className="text-xs text-muted">{UNIT_KIND_LABEL[unit.unitKind]}</span>
      <Input className="w-40" aria-label={`${unit.name} 이름`} value={name} maxLength={200} onChange={(e) => setName(e.target.value)} />
      {dirtyName && (
        <Button size="sm" onClick={() => rename.mutate()} disabled={rename.isPending}>
          이름 저장
        </Button>
      )}
      <label className="text-xs text-muted">
        수량
        <Input className="mt-1 w-16" type="number" min={1} max={999} aria-label={`${unit.name} 수량`} value={totalUnits} onChange={(e) => setTotalUnits(e.target.value)} />
      </label>
      {dirtyCapacity && (
        <Button size="sm" onClick={() => capacity.mutate()} disabled={capacity.isPending}>
          수량 저장
        </Button>
      )}
      <span className="text-xs text-muted">기본 {unit.basePrice.toLocaleString('ko-KR')}원</span>
      <ErrorLine error={rename.error} />
      <CapacityError error={capacity.error} />
    </li>
  );
}

// --- 오류 표시 ---------------------------------------------------------------

function ErrorLine({ error }: { error: unknown }) {
  if (!error) return null;
  return (
    <p role="alert" className="w-full text-xs text-warn">
      {error instanceof ApiError ? error.message : '요청이 실패했습니다. 잠시 뒤 다시 시도해 주세요.'}
    </p>
  );
}

/**
 * 수량 줄이기가 막힌 경우. 서버의 `details` 가 막는 날짜들이다 — 그대로 보여 준다.
 * 날짜를 알아야 어느 예약을 옮기거나 취소할지 고를 수 있다.
 */
function CapacityError({ error }: { error: unknown }) {
  if (!error) return null;
  if (error instanceof ApiError && error.code === 'CAPACITY_BELOW_BOOKINGS') {
    const dates = error.body?.details ?? [];
    return (
      <p role="alert" className="w-full text-xs text-warn" data-testid="capacity-blocked">
        이미 팔린 날이 있어 그 수량으로 줄일 수 없습니다. 막는 날짜: {dates.join(', ')}
      </p>
    );
  }
  return <ErrorLine error={error} />;
}
