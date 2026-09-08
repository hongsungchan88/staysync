import { useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchProperties } from '@/api/calendar';
import { fetchReport } from '@/api/reports';
import type { ChannelShare } from '@/api/schemas';
import { Input } from '@/components/ui/input';

/**
 * 운영 리포트. 계획서 8.8 의 `/reports` 다.
 *
 * **기간의 뜻은 숙박으로 통일돼 있다.** 지표 여섯이 한 화면에 있으므로 지표마다
 * 기간의 뜻이 다르면 숫자가 서로 안 맞는다. "8월 취소율"은 8월에 예약한 건이 아니라
 * **8월에 묵기로 했던 예약 중 취소된 비율**이다. 그 판정은 서버가 한다.
 *
 * **화면이 나누지 않는다.** 비율도 평균도 서버가 계산해 보낸다. 여기서 다시 나누면
 * 0으로 나누는 규칙이 두 곳에 생기고, 둘이 어긋나면 화면만 조용히 이상해진다.
 *
 * `RevPAR = ADR × 점유율` 이 성립한다 — 점유율과 RevPAR 의 분모가 같은 값이기
 * 때문이다. 셋을 나란히 놓는 이유가 그것이고, 서버 테스트가 그 항등식을 잡고 있다.
 */
export function ReportsPage() {
  const [range, setRange] = useState(() => currentMonth());
  const [propertyId, setPropertyId] = useState<number | undefined>(undefined);

  const properties = useQuery({ queryKey: ['properties'], queryFn: fetchProperties });

  const filter = useMemo(
    () => ({ propertyId, from: range.from, to: range.to }),
    [propertyId, range.from, range.to],
  );
  const report = useQuery({
    queryKey: ['report', propertyId ?? 'all', range.from, range.to] as const,
    queryFn: () => fetchReport(filter),
    // 기간이 비면 서버가 400 을 준다. 사용자가 날짜를 지우는 중일 수 있다.
    enabled: Boolean(range.from && range.to),
  });

  const m = report.data;

  return (
    <div className="mx-auto flex h-full w-full max-w-5xl flex-col gap-5 overflow-auto p-6">
      <header className="flex items-baseline justify-between">
        <h1 className="text-lg font-semibold text-ink">리포트</h1>
        <Link to="/" className="text-sm text-clay underline-offset-2 hover:underline">
          캘린더로
        </Link>
      </header>

      <section className="flex flex-wrap items-end gap-3" aria-label="필터">
        <label className="text-xs text-muted">
          숙소
          <select
            className="mt-1 h-9 w-48 rounded-md border border-rule-strong bg-paper px-2 text-sm text-ink"
            aria-label="숙소"
            value={propertyId ?? ''}
            onChange={(event) =>
              setPropertyId(event.target.value ? Number(event.target.value) : undefined)
            }
          >
            <option value="">전체</option>
            {(properties.data ?? []).map((property) => (
              <option key={property.id} value={property.id}>
                {property.name}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs text-muted">
          시작
          <Input
            type="date"
            className="mt-1"
            aria-label="시작 날짜"
            value={range.from}
            onChange={(event) => setRange((r) => ({ ...r, from: event.target.value }))}
          />
        </label>
        <label className="text-xs text-muted">
          끝
          <Input
            type="date"
            className="mt-1"
            aria-label="끝 날짜"
            value={range.to}
            onChange={(event) => setRange((r) => ({ ...r, to: event.target.value }))}
          />
        </label>
      </section>

      {report.isLoading && <p className="text-sm text-muted">불러오는 중입니다…</p>}
      {report.isError && (
        <p role="alert" className="rounded-md border border-clay bg-paper p-3 text-sm text-clay">
          리포트를 불러오지 못했습니다. 기간을 확인해 주세요.
        </p>
      )}

      {m && (
        <>
          {/*
            **판매된 것이 없는 기간과 0인 지표를 구분해 적는다.** 둘 다 숫자가 0이지만
            뜻이 다르다. 새 숙소를 등록한 직후가 앞의 경우이고, 그때 0%가 성과처럼
            읽히면 안 된다.
          */}
          {m.soldNights === 0 && (
            <p className="rounded-md border border-rule bg-sand/40 p-3 text-sm text-muted">
              이 기간에 판매된 객실박이 없습니다. 아래 지표는 모두 0입니다.
            </p>
          )}

          <section className="grid grid-cols-2 gap-3 lg:grid-cols-3" aria-label="지표">
            <Metric label="점유율" value={percent(m.occupancyRate)} testId="metric-occupancy">
              판매된 {m.soldNights} / 판매 가능 {m.availableNights} 객실박
            </Metric>
            <Metric label="ADR" value={won(m.adr)} testId="metric-adr">
              객실 매출 ÷ 판매된 객실박
            </Metric>
            <Metric label="RevPAR" value={won(m.revPar)} testId="metric-revpar">
              ADR × 점유율과 같다
            </Metric>
            <Metric
              label="리드타임"
              value={`${m.leadTimeDays.toFixed(1)}일`}
              testId="metric-leadtime"
            >
              예약일부터 체크인일까지 평균
            </Metric>
            <Metric
              label="취소율"
              value={percent(m.cancellationRate)}
              testId="metric-cancellation"
            >
              숙박 기준. 만료된 홀드는 세지 않는다
            </Metric>
            <Metric label="객실 매출" value={won(m.roomRevenue)} testId="metric-revenue">
              확정 이상만. HOLD 는 세지 않는다
            </Metric>
          </section>

          <ChannelMix mix={m.channelMix} />
        </>
      )}
    </div>
  );
}

function Metric({
  label,
  value,
  testId,
  children,
}: {
  label: string;
  value: string;
  testId: string;
  children: ReactNode;
}) {
  return (
    <article className="rounded-md border border-rule bg-paper p-3" data-testid={testId}>
      <h2 className="text-xs text-muted">{label}</h2>
      <p className="mt-1 text-xl font-semibold text-ink">{value}</p>
      <p className="mt-1 text-xs text-muted">{children}</p>
    </article>
  );
}

/** 채널 믹스. 건수는 예약 수이고 박 수가 아니다. 서버가 매출 내림차순으로 보낸다. */
function ChannelMix({ mix }: { mix: ChannelShare[] }) {
  return (
    <section aria-label="채널 믹스" className="flex flex-col gap-2">
      <h2 className="text-sm font-semibold text-ink">채널 믹스</h2>
      {mix.length === 0 ? (
        <p className="text-sm text-muted">이 기간에 판매된 예약이 없습니다.</p>
      ) : (
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="border-b border-rule text-left text-xs text-muted">
              <th className="py-1 font-normal">채널</th>
              <th className="py-1 text-right font-normal">예약</th>
              <th className="py-1 text-right font-normal">매출</th>
              <th className="py-1 text-right font-normal">비중</th>
            </tr>
          </thead>
          <tbody>
            {mix.map((share) => (
              <tr key={share.channelCode} className="border-b border-rule/60">
                <td className="py-1 text-ink">{share.channelCode}</td>
                <td className="py-1 text-right text-ink">{share.reservations}건</td>
                <td className="py-1 text-right text-ink">{won(share.revenue)}</td>
                <td className="py-1 text-right text-muted">{percent(share.revenueShare)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

// --- 표시 ---------------------------------------------------------------------

/** 0.0 ~ 1.0 을 백분율로. 서버가 이미 나눈 값이므로 여기서는 곱하기만 한다. */
function percent(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`;
}

function won(amount: number): string {
  return `${Math.round(amount).toLocaleString('ko-KR')}원`;
}

/**
 * 기본 기간은 이번 달이다.
 *
 * **`toISOString()` 을 쓰지 않는다.** 그것은 UTC 로 바꾸므로 한국 시간으로 매월 1일
 * 0~9시 사이에 열면 지난달이 뜬다. 15주차에 시각을 잘라 쓰다 9시간 어긋난 것과 같은
 * 함정이고, 이쪽은 날짜라 하루가 통째로 밀린다.
 */
function currentMonth(): { from: string; to: string } {
  const now = new Date();
  const first = new Date(now.getFullYear(), now.getMonth(), 1);
  // 다음 달 0일은 이번 달 마지막 날이다. 말일을 직접 세지 않는다.
  const last = new Date(now.getFullYear(), now.getMonth() + 1, 0);
  return { from: isoDate(first), to: isoDate(last) };
}

function isoDate(at: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}`;
}
