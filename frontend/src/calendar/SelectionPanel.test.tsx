import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { SelectionPanel } from './SelectionPanel';
import { resetRefreshState } from '@/api/client';
import { tokenStore } from '@/auth/tokenStore';
import type { CalendarGrid } from '@/api/schemas';
import { addDays } from '@/lib/dates';

/**
 * 요금·제약 편집 패널.
 *
 * **미리보기와 적용이 같은 요청이어야 한다는 것**이 여기서 지킬 계약이다. 서버가
 * `dryRun` 하나로 묶어 둔 것을 화면이 두 갈래로 나누면 묶어 둔 의미가 없어진다.
 */

const FROM = '2027-03-01';
const KEY = ['calendar', 1, FROM, addDays(FROM, 29)] as const;

function grid(): CalendarGrid {
  return {
    from: FROM,
    to: addDays(FROM, 29),
    units: Array.from({ length: 3 }, (_, u) => ({
      id: u + 1,
      name: `객실 0${u + 1}`,
      totalUnits: 1,
      days: Array.from({ length: 30 }, (_, d) => ({
        date: addDays(FROM, d),
        avail: 1,
        price: 90000,
        minStay: 1,
        stopSell: false,
        conflict: false,
      })),
    })),
    reservations: [],
  };
}

let client: QueryClient;
let fetchMock: ReturnType<typeof vi.fn>;

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function renderPanel(rect = { fromUnit: 0, toUnit: 1, fromDate: 0, toDate: 6 }) {
  return render(
    <SelectionPanel data={grid()} rect={rect} propertyId={1} calendarKey={KEY} />,
    { wrapper },
  );
}

function jsonResponse(status: number, body: unknown) {
  return { ok: status < 400, status, json: async () => body };
}

function result(overrides: Record<string, unknown> = {}) {
  return {
    unitCount: 2,
    dayCount: 7,
    cellCount: 14,
    changed: ['price'],
    dryRun: true,
    ...overrides,
  };
}

/** 마지막 요청의 본문. */
function lastBody() {
  const calls = fetchMock.mock.calls;
  return JSON.parse(calls[calls.length - 1]![1].body);
}

beforeEach(() => {
  resetRefreshState();
  tokenStore.set('테스트-토큰');
  client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
  resetRefreshState();
});

describe('요금·제약 편집 패널', () => {
  it('선택이 없으면 안내만 보여 준다', () => {
    render(<SelectionPanel data={grid()} rect={null} propertyId={1} calendarKey={KEY} />, {
      wrapper,
    });

    expect(screen.getByTestId('selection-panel')).toHaveTextContent('셀을 끌면');
    expect(screen.queryByTestId('apply-button')).not.toBeInTheDocument();
  });

  it('선택 범위와 적용 대상을 그대로 보여 준다', () => {
    renderPanel();

    expect(screen.getByTestId('selection-range')).toHaveTextContent(
      `${FROM} ~ ${addDays(FROM, 6)} (7일)`,
    );
    expect(screen.getByTestId('selection-units')).toHaveTextContent('객실 01');
    expect(screen.getByTestId('selection-units')).toHaveTextContent('객실 02');
    expect(screen.getByTestId('selection-units')).not.toHaveTextContent('객실 03');
  });

  it('바꿀 항목이 없으면 미리보기와 적용이 눌리지 않는다', () => {
    renderPanel();

    // 아무것도 정하지 않은 채 적용하면 서버가 거부한다. 그 왕복을 만들지 않는다.
    expect(screen.getByTestId('preview-button')).toBeDisabled();
    expect(screen.getByTestId('apply-button')).toBeDisabled();
  });

  it('미리보기와 적용이 같은 요청을 dryRun 만 다르게 보낸다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, result()));
    renderPanel();

    fireEvent.click(screen.getByTestId('price-mode-fixed'));
    fireEvent.change(screen.getByTestId('price-input'), { target: { value: '250000' } });
    fireEvent.click(screen.getByTestId('weekday-FRIDAY'));

    fireEvent.click(screen.getByTestId('preview-button'));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const 미리보기 = lastBody();

    fetchMock.mockResolvedValue(jsonResponse(200, result({ dryRun: false })));
    fireEvent.click(screen.getByTestId('apply-button'));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    const 적용 = lastBody();

    // dryRun 을 빼면 두 본문이 완전히 같아야 한다. 여기가 갈라지면 미리보기가
    // 실제와 다른 것을 세게 되고, 그건 사용자가 적용한 뒤에야 드러난다.
    expect(미리보기.dryRun).toBe(true);
    expect(적용.dryRun).toBe(false);
    const { dryRun: _a, ...미리보기나머지 } = 미리보기;
    const { dryRun: _b, ...적용나머지 } = 적용;
    expect(미리보기나머지).toEqual(적용나머지);

    expect(적용나머지).toMatchObject({
      unitIds: [1, 2],
      from: FROM,
      to: addDays(FROM, 6),
      weekdays: ['FRIDAY'],
      priceMode: 'FIXED',
      price: 250000,
    });
  });

  it('%는 비율로 바꿔 보낸다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, result()));
    renderPanel();

    fireEvent.click(screen.getByTestId('price-mode-percent'));
    fireEvent.change(screen.getByTestId('percent-input'), { target: { value: '20' } });
    fireEvent.click(screen.getByTestId('preview-button'));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    // 화면은 %로 받고 서버는 비율로 받는다. 여기서 100을 빠뜨리면 20배가 된다.
    expect(lastBody()).toMatchObject({ priceMode: 'PERCENT', priceRate: 0.2 });
  });

  it('미리보기 결과는 바뀔 칸 수를 보여 주고 아무것도 다시 불러오지 않는다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, result()));
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    renderPanel();

    fireEvent.change(screen.getByTestId('min-stay-input'), { target: { value: '2' } });
    fireEvent.click(screen.getByTestId('preview-button'));

    await waitFor(() =>
      expect(screen.getByTestId('bulk-edit-preview')).toHaveTextContent('14칸이 바뀝니다'),
    );
    // 미리보기는 아무것도 쓰지 않으므로 다시 불러올 것도 없다.
    expect(invalidate).not.toHaveBeenCalled();
  });

  it('적용하면 캘린더를 다시 불러온다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, result({ dryRun: false })));
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    renderPanel();

    fireEvent.click(screen.getByTestId('stop-sell-input'));
    fireEvent.click(screen.getByTestId('apply-button'));

    await waitFor(() =>
      expect(screen.getByTestId('bulk-edit-applied')).toHaveTextContent('14칸을 바꿨습니다'),
    );
    // 적용하면 요금뿐 아니라 판매중지가 잔여 재고 표시를 바꾼다. 다시 불러오지 않으면
    // 화면이 원장과 어긋난 채 남는다.
    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith(expect.objectContaining({ queryKey: KEY })),
    );
  });

  it('서버가 거절하면 이유를 그대로 보여 준다', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, {
        code: 'INVALID_BULK_EDIT',
        message: '요일 필터를 적용하면 해당하는 날짜가 없습니다.',
        details: [],
      }),
    );
    renderPanel();

    fireEvent.change(screen.getByTestId('min-stay-input'), { target: { value: '2' } });
    fireEvent.click(screen.getByTestId('apply-button'));

    await waitFor(() =>
      expect(screen.getByTestId('bulk-edit-error')).toHaveTextContent('해당하는 날짜가 없습니다'),
    );
    // 실패했는데 결과 문구가 남아 있으면 적용된 것으로 읽힌다.
    expect(screen.queryByTestId('bulk-edit-applied')).not.toBeInTheDocument();
  });

  it('선택을 바꾸면 앞의 미리보기가 사라진다', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, result()));
    const { rerender } = renderPanel();

    fireEvent.change(screen.getByTestId('min-stay-input'), { target: { value: '2' } });
    fireEvent.click(screen.getByTestId('preview-button'));
    await waitFor(() => expect(screen.getByTestId('bulk-edit-preview')).toBeInTheDocument());

    rerender(
      <SelectionPanel
        data={grid()}
        rect={{ fromUnit: 0, toUnit: 0, fromDate: 10, toDate: 12 }}
        propertyId={1}
        calendarKey={KEY}
      />,
    );

    // 남겨 두면 지금 고른 범위의 결과로 읽힌다. 14칸은 앞 범위의 수다.
    expect(screen.queryByTestId('bulk-edit-preview')).not.toBeInTheDocument();
  });
});
