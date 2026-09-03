import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { CalendarGrid } from './CalendarGrid';
import { DAY_W, HEAD_H, LANE_H, LEFT_W, ROW_PAD, TEXT_H, layoutGrid } from './layout';
import type { CalendarGrid as GridData, ReservationBar } from '@/api/schemas';
import type { SelectionRect } from './selection';
import type { MoveRequest } from './useReservationMove';
import { addDays } from '@/lib/dates';

/**
 * 완료 조건 1·2·3. 가상 스크롤.
 *
 * 7주차 측정에서 30객실 × 90일이 DOM 노드 11,229개를 만들었고, 그 위에서 강제 레이아웃
 * 한 번이 130 ms 였다(`docs/측정-01-캘린더-초기렌더링.md`). 여기서 지키는 계약은 두 가지다 —
 * **보이는 것만 그린다**, 그리고 **행 높이가 제각각이어도 스크롤 위치가 어긋나지 않는다**.
 *
 * 뒤엣것이 이 파일의 핵심이다. 행 높이가 판매 단위마다 다른 것은 D 의 쌓기 규칙 때문에
 * 실제로 벌어지는 일이고, 고정 높이를 전제로 계산하면 스크롤할수록 어긋나 엉뚱한 방의
 * 줄에 남의 예약이 놓인다. 눈으로는 늦게 발견되고 화면만 보면 정상으로 보인다.
 */

const FROM = '2026-09-01';
const VIEWPORT = { width: 800, height: 600 };

/**
 * jsdom 은 배치를 하지 않아 크기가 전부 0 이다. 가상 스크롤은 크기가 있어야 시작한다.
 *
 * `@tanstack/react-virtual` 이 보는 것은 `offsetWidth`/`offsetHeight` 다.
 * `getBoundingClientRect` 를 대신 흉내 내면 아무 항목도 그려지지 않아, 테스트가 통과하는데
 * 화면은 비어 있는 상태가 된다.
 */
function stubViewport() {
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(VIEWPORT.width);
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(VIEWPORT.height);
}

function bar(id: number, unitId: number, from: number, nights: number): ReservationBar {
  return {
    id,
    unitId,
    checkIn: addDays(FROM, from),
    checkOut: addDays(FROM, from + nights),
    guestName: `게스트${id}`,
    channel: 'DIRECT',
    status: 'CONFIRMED',
    amount: 100000,
  };
}

/**
 * 측정과 같은 규모의 가짜 그리드. 판매 단위 수와 예약을 바꿔 가며 쓴다.
 *
 * `barsOf` 로 단위마다 예약 수를 다르게 주면 행 높이가 제각각이 된다. 어긋남을 잡으려면
 * 그 상태여야 한다.
 */
function grid(
  units: number,
  days: number,
  barsOf: (unitIndex: number) => ReservationBar[] = () => [],
): GridData {
  return {
    from: FROM,
    to: addDays(FROM, days - 1),
    units: Array.from({ length: units }, (_, u) => ({
      id: u + 1,
      name: `객실 ${String(u + 1).padStart(2, '0')}`,
      totalUnits: 4,
      days: Array.from({ length: days }, (_, d) => ({
        date: addDays(FROM, d),
        avail: 2,
        price: 120000,
        minStay: 1,
        stopSell: false,
        conflict: false,
      })),
    })),
    reservations: Array.from({ length: units }, (_, u) => barsOf(u)).flat(),
  };
}

/** 선택과 이동 콜백은 이 파일의 관심사가 아니다. 필요한 테스트만 따로 넘긴다. */
function renderGrid(data: GridData, props: Partial<ComponentProps<typeof CalendarGrid>> = {}) {
  return render(
    <CalendarGrid data={data} onSelect={() => {}} onMove={() => {}} {...props} />,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('가상 스크롤', () => {
  it('30객실 × 90일에서 보이는 만큼만 그린다', () => {
    stubViewport();
    const data = grid(30, 90, (u) => [bar(u * 3 + 1, u + 1, u % 60, 3)]);

    const { container } = renderGrid(data);

    // 7주차는 같은 데이터로 11,229개였다. 화면에 들어가는 셀은 세로 10행 × 가로 12칸
    // 남짓이라 노드가 자릿수로 줄어야 한다. 여유를 두되 자릿수는 지킨다.
    const nodes = container.querySelectorAll('*').length;
    expect(nodes).toBeLessThan(1500);

    // 그리지 않는 것과 못 그리는 것은 다르다. 첫 칸은 실제로 있어야 한다.
    expect(screen.getByTestId(`cell-1-${FROM}`)).toBeInTheDocument();
    // 90일째는 아직 화면 밖이다.
    expect(screen.queryByTestId(`cell-1-${addDays(FROM, 89)}`)).not.toBeInTheDocument();
  });

  it('스크롤 범위는 전부 그린 것과 같다', () => {
    stubViewport();
    const data = grid(30, 90, (u) => [bar(u + 1, u + 1, 0, 2)]);
    const layouts = layoutGrid(data);

    const { container } = renderGrid(data);

    // 여백으로 밀어 두었으므로 안쪽 내용의 크기가 전체 데이터 기준이어야 한다.
    // 여기가 틀리면 스크롤 막대 길이가 실제 데이터와 어긋난다.
    const inner = container.querySelector('[data-testid="calendar-grid"] > div') as HTMLElement;
    expect(inner.style.width).toBe(`${LEFT_W + 90 * DAY_W}px`);

    const expectedHeight =
      HEAD_H + data.units.reduce((sum, unit) => sum + layouts.get(unit.id)!.height, 0);
    const rendered = [...inner.children]
      .slice(1) // 첫 자식은 날짜 헤더다
      .reduce((sum, el) => sum + Number.parseFloat((el as HTMLElement).style.height || '0'), 0);
    expect(HEAD_H + rendered).toBe(expectedHeight);
  });

  it('행 높이가 단위마다 달라도 스크롤 위치가 어긋나지 않는다', () => {
    stubViewport();

    // 판매 단위마다 겹치는 예약 수를 다르게 준다. 0·1·2·3줄이 섞여 행 높이가 제각각이 된다.
    const data = grid(30, 90, (u) => {
      const overlapping = u % 4;
      return Array.from({ length: overlapping }, (_, i) => bar(u * 10 + i, u + 1, 0, 5));
    });
    const layouts = layoutGrid(data);
    const heights = data.units.map((unit) => layouts.get(unit.id)!.height);

    // 전제 확인. 높이가 전부 같으면 이 테스트는 아무것도 잡지 못한다.
    expect(new Set(heights).size).toBeGreaterThan(1);

    renderGrid(data);
    const scroller = screen.getByTestId('calendar-grid');

    // 여러 지점에서 확인한다. 한 곳만 보면 우연히 맞을 수 있다.
    for (const scrollTop of [0, 150, 400, 900, 1500]) {
      Object.defineProperty(scroller, 'scrollTop', { value: scrollTop, configurable: true });
      fireEvent.scroll(scroller);

      // 이 스크롤 위치에서 화면 맨 위에 있어야 할 판매 단위를 테스트가 직접 셈한다.
      let offset = HEAD_H;
      let expectedUnit = data.units[0]!;
      for (const [index, height] of heights.entries()) {
        if (offset + height > scrollTop) {
          expectedUnit = data.units[index]!;
          break;
        }
        offset += height;
      }

      const row = screen.getByTestId(`row-${expectedUnit.id}`);
      expect(row, `scrollTop=${scrollTop} 에서 ${expectedUnit.name} 이 보여야 한다`)
        .toBeInTheDocument();
      // 그 행의 높이도 줄 수에서 나온 값 그대로여야 한다.
      expect(row.style.height).toBe(`${layouts.get(expectedUnit.id)!.height}px`);
    }
  });

  it('가로로 스크롤해도 좌측 목록과 날짜 헤더가 남는다', () => {
    stubViewport();
    const data = grid(5, 90);

    renderGrid(data);
    const scroller = screen.getByTestId('calendar-grid');

    Object.defineProperty(scroller, 'scrollLeft', { value: 2000, configurable: true });
    fireEvent.scroll(scroller);

    // 가로로 멀리 밀어도 좌측 목록은 문서에 남아 있어야 한다. 절대 위치로 흩뿌리는 방식을
    // 쓰면 여기서 사라지고, sticky 가 죽어 어느 방인지 알 수 없게 된다.
    expect(screen.getByText('객실 01')).toBeInTheDocument();
    expect(screen.getByTestId('date-header')).toBeInTheDocument();
    // 그 자리의 날짜 칸은 30일째 언저리다. 첫날은 이미 밀려났다.
    expect(screen.queryByTestId(`cell-1-${FROM}`)).not.toBeInTheDocument();
  });

  it('상한을 넘긴 예약은 "+N" 으로 접히고 눌러서 볼 수 있다', () => {
    stubViewport();
    // 완료 조건 10 의 화면 쪽. 계산은 layout.test.ts 가 따로 확인한다.
    const data = grid(1, 30, () => [
      bar(1, 1, 2, 3),
      bar(2, 1, 2, 3),
      bar(3, 1, 2, 3),
      bar(4, 1, 2, 3),
    ]);

    renderGrid(data);

    const fold = screen.getByTestId(`folded-1-${addDays(FROM, 2)}`);
    expect(fold).toHaveTextContent('+1');
    // 세 줄까지만 그린다.
    expect(screen.getAllByTestId(/^bar-/)).toHaveLength(3);
    expect(screen.getByTestId('row-1').style.height).toBe(`${TEXT_H + 3 * LANE_H + ROW_PAD}px`);

    fireEvent.click(fold);
    expect(screen.getByTestId(`folded-list-1-${addDays(FROM, 2)}`)).toHaveTextContent('게스트4');
  });
});

/**
 * 완료 조건 4·8·9. 그리드 위의 조작.
 *
 * 좌표 계산 자체는 `selection.test.ts` 가 따로 확인한다. 여기서는 그 계산이 실제 이벤트에
 * 이어져 있는지, 그리고 옮기면 안 되는 막대가 잡히지 않는지를 본다.
 */
describe('그리드 조작', () => {
  /** jsdom 은 포인터 캡처를 구현하지 않는다. 선택은 캡처를 잡고 시작한다. */
  function stubPointerCapture() {
    Element.prototype.setPointerCapture = vi.fn();
    Element.prototype.releasePointerCapture = vi.fn();
    Element.prototype.hasPointerCapture = vi.fn().mockReturnValue(false);
  }

  it('여러 판매 단위에 걸쳐 기간을 고를 수 있다', () => {
    stubViewport();
    stubPointerCapture();
    const data = grid(5, 30);

    const selections: (SelectionRect | null)[] = [];
    renderGrid(data, { onSelect: (rect) => selections.push(rect) });
    const scroller = screen.getByTestId('calendar-grid');

    // jsdom 의 getBoundingClientRect 가 0 이라 clientX/Y 가 곧 컨테이너 기준 좌표다.
    // 객실 01 의 3일째에서 눌러 객실 03 의 6일째까지 끈다.
    fireEvent.pointerDown(scroller, {
      button: 0,
      pointerId: 1,
      clientX: LEFT_W + 2 * DAY_W + 5,
      clientY: HEAD_H + 5,
    });
    fireEvent.pointerMove(scroller, {
      pointerId: 1,
      clientX: LEFT_W + 5 * DAY_W + 5,
      clientY: HEAD_H + 2 * 66 + 5,
    });
    fireEvent.pointerUp(scroller, { pointerId: 1 });

    // 8.4 의 편집 패널이 적용 대상에 판매 단위를 여러 개 받으므로, 세로로도 걸쳐야 한다.
    expect(selections.at(-1)).toEqual({ fromUnit: 0, toUnit: 2, fromDate: 2, toDate: 5 });

    // 고른 칸이 화면에도 표시돼야 한다. 패널에만 나오면 어디를 골랐는지 알 수 없다.
    expect(screen.getByTestId(`cell-2-${addDays(FROM, 3)}`)).toHaveAttribute('data-selected');
    expect(screen.getByTestId(`cell-4-${addDays(FROM, 3)}`)).not.toHaveAttribute('data-selected');
  });

  it('막대를 잡으면 기간 선택이 시작되지 않는다', () => {
    stubViewport();
    stubPointerCapture();
    const data = grid(3, 30, (u) => (u === 0 ? [bar(1, 1, 1, 3)] : []));

    const selections: (SelectionRect | null)[] = [];
    renderGrid(data, { onSelect: (rect) => selections.push(rect) });

    // 같은 포인터 이벤트를 둘이 나눠 갖는다. 막대 위에서 누르면 이동이지 선택이 아니다.
    fireEvent.pointerDown(screen.getByTestId('bar-1'), {
      button: 0,
      pointerId: 1,
      clientX: LEFT_W + DAY_W,
      clientY: HEAD_H + 20,
    });

    expect(selections).toHaveLength(0);
  });

  it('취소·체크아웃된 예약은 잡히지 않는다', () => {
    stubViewport();
    const data = grid(1, 30, () => [
      // 화면에 들어오는 앞쪽 열에 나란히 둔다. 가로 가상 스크롤 때문에 뒤쪽 날짜의
      // 막대는 아예 그려지지 않아 이 테스트가 확인하려는 것을 못 본다.
      { ...bar(1, 1, 0, 1), status: 'CONFIRMED' },
      { ...bar(2, 1, 2, 1), status: 'HOLD' },
      { ...bar(3, 1, 4, 1), status: 'CANCELLED' },
      { ...bar(4, 1, 6, 1), status: 'CHECKED_OUT' },
      { ...bar(5, 1, 8, 1), status: 'CHECKED_IN' },
    ]);

    renderGrid(data);

    // 백엔드 Reservation.isActive() 와 같은 집합이다. 잡히기만 하고 놓을 때마다
    // 거절당하면 왜 안 되는지 알 수 없다.
    expect(screen.getByTestId('bar-1')).toHaveAttribute('data-movable');
    expect(screen.getByTestId('bar-2')).toHaveAttribute('data-movable');
    expect(screen.getByTestId('bar-3')).not.toHaveAttribute('data-movable');
    expect(screen.getByTestId('bar-4')).not.toHaveAttribute('data-movable');
    expect(screen.getByTestId('bar-5')).not.toHaveAttribute('data-movable');

    // 잡히지 않는 막대는 포커스도 받지 않아야 키보드로도 못 옮긴다는 것이 일관된다.
    expect(screen.getByTestId('bar-3')).not.toHaveAttribute('tabindex');
    expect(screen.getByTestId('bar-1')).toHaveAttribute('tabindex', '0');
  });

  it('보이지 않는 행이 갱신돼도 스크롤 위치가 흔들리지 않는다', () => {
    stubViewport();
    stubPointerCapture();
    const data = grid(30, 90, (u) => [bar(u + 1, u + 1, 0, 2)]);

    const { rerender } = renderGrid(data);
    const scroller = screen.getByTestId('calendar-grid');

    // 한참 아래로 내려 둔다. 실시간 갱신이 오는 시점의 흔한 상태다.
    Object.defineProperty(scroller, 'scrollTop', { value: 900, configurable: true, writable: true });
    Object.defineProperty(scroller, 'scrollLeft', { value: 600, configurable: true, writable: true });
    fireEvent.scroll(scroller);
    const 보이던행 = screen.getAllByTestId(/^row-/).map((el) => el.dataset.testid);

    // SSE 가 알린 뒤 다시 조회해 온 데이터. 화면 밖의 객실 30 에 예약이 하나 붙었다.
    const 갱신됨: GridData = {
      ...data,
      reservations: [...data.reservations, bar(9999, 30, 40, 3)],
    };
    rerender(<CalendarGrid data={갱신됨} onSelect={() => {}} onMove={() => {}} />);

    // 스크롤 위치를 건드리면 사용자가 보던 자리에서 화면이 튄다. 예약 하나가
    // 들어올 때마다 튀면 실시간 갱신이 방해가 된다.
    expect(scroller.scrollTop).toBe(900);
    expect(scroller.scrollLeft).toBe(600);
    expect(screen.getAllByTestId(/^row-/).map((el) => el.dataset.testid)).toEqual(보이던행);
  });

  it('키보드만으로 막대를 옮길 수 있다', async () => {
    stubViewport();
    const data = grid(1, 30, () => [bar(1, 1, 3, 2)]);

    const moves: MoveRequest[] = [];
    renderGrid(data, { onMove: (request) => moves.push(request) });

    // 드래그로만 되는 조작은 마우스가 없으면 못 쓰는 기능이 된다. dnd-kit 의 키보드
    // 센서에 칸 너비를 물려 두었으므로 한 번 누르면 하루다.
    const target = screen.getByTestId('bar-1');
    target.focus();
    fireEvent.keyDown(target, { key: ' ', code: 'Space' }); // 잡기

    // 잡은 뒤로는 dnd-kit 이 document 에서 키를 듣는데, 그 등록이 setTimeout 뒤에 일어난다.
    // 곧바로 방향키를 쏘면 아직 아무도 듣고 있지 않다.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    fireEvent.keyDown(document, { key: 'ArrowRight', code: 'ArrowRight' });
    fireEvent.keyDown(document, { key: 'ArrowRight', code: 'ArrowRight' });
    fireEvent.keyDown(document, { key: ' ', code: 'Space' }); // 놓기

    await waitFor(() => expect(moves).toEqual([{ reservationId: 1, dayDelta: 2 }]));
  });
});
