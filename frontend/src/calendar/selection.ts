import { DAY_W, HEAD_H, LEFT_W } from './layout';

/**
 * 기간 선택의 좌표 계산.
 *
 * 커서 아래의 셀을 `elementFromPoint` 로 찾지 않고 **좌표에서 직접 계산한다.** 가상
 * 스크롤 때문에 화면 밖 칸은 DOM 에 아예 없고, 자동 스크롤이 도는 동안에는 커서가
 * 컨테이너 밖에 있어 그 아래에 아무 요소도 없기 때문이다. 좌표 계산은 두 경우 모두
 * 같은 식으로 답을 낸다.
 */

/** 그리드 안의 한 칸. 둘 다 인덱스이고 화면 픽셀이 아니다. */
export interface Cell {
  unitIndex: number;
  dateIndex: number;
}

export interface SelectionRect {
  fromUnit: number;
  toUnit: number;
  fromDate: number;
  toDate: number;
}

/** 스크롤 컨테이너 기준의 커서 위치. */
export interface PointerPosition {
  /** 컨테이너 왼쪽 위 모서리에서 잰 값. 컨테이너 밖이면 음수이거나 크기를 넘는다. */
  offsetX: number;
  offsetY: number;
  scrollLeft: number;
  scrollTop: number;
}

/**
 * 커서 위치를 칸으로 바꾼다.
 *
 * 좌측 목록과 날짜 헤더가 차지하는 몫을 빼고 나눈다. 범위를 벗어나면 **가장자리로
 * 접는다.** 자동 스크롤 중에는 커서가 컨테이너 밖에 있는 것이 정상이고, 그때도 선택은
 * 가장자리 칸까지 이어져야 한다.
 */
export function cellAt(
  pointer: PointerPosition,
  rowOffsets: number[],
  totalDays: number,
): Cell {
  const x = pointer.offsetX + pointer.scrollLeft - LEFT_W;
  const dateIndex = clamp(Math.floor(x / DAY_W), 0, totalDays - 1);

  const y = pointer.offsetY + pointer.scrollTop - HEAD_H;
  return { unitIndex: unitIndexAt(y, rowOffsets), dateIndex };
}

/**
 * 세로 위치에 해당하는 판매 단위.
 *
 * 행 높이가 단위마다 달라 나눗셈 한 번으로 못 구한다. `rowOffsets` 는 각 행이 시작하는
 * 위치이고 마지막 항목이 전체 높이라, 그 위에서 이분 탐색한다.
 */
function unitIndexAt(y: number, rowOffsets: number[]): number {
  const count = rowOffsets.length - 1;
  if (count <= 0) {
    return 0;
  }
  let low = 0;
  let high = count - 1;
  while (low < high) {
    const mid = (low + high + 1) >> 1;
    if (rowOffsets[mid]! <= y) {
      low = mid;
    } else {
      high = mid - 1;
    }
  }
  return clamp(low, 0, count - 1);
}

/** 시작 칸과 끝 칸으로 사각형을 만든다. 어느 방향으로 끌어도 같은 결과여야 한다. */
export function rectOf(anchor: Cell, head: Cell): SelectionRect {
  return {
    fromUnit: Math.min(anchor.unitIndex, head.unitIndex),
    toUnit: Math.max(anchor.unitIndex, head.unitIndex),
    fromDate: Math.min(anchor.dateIndex, head.dateIndex),
    toDate: Math.max(anchor.dateIndex, head.dateIndex),
  };
}

export function rectContains(rect: SelectionRect, unitIndex: number, dateIndex: number): boolean {
  return (
    unitIndex >= rect.fromUnit &&
    unitIndex <= rect.toUnit &&
    dateIndex >= rect.fromDate &&
    dateIndex <= rect.toDate
  );
}

/** 행이 시작하는 세로 위치의 누적합. 마지막 항목이 전체 높이다. */
export function rowOffsetsOf(heights: number[]): number[] {
  const offsets = [0];
  for (const height of heights) {
    offsets.push(offsets[offsets.length - 1]! + height);
  }
  return offsets;
}

/** 자동 스크롤이 시작되는 가장자리 폭. */
export const EDGE = 48;
/** 한 프레임에 밀어내는 거리. 너무 크면 선택이 통제되지 않는다. */
const STEP = 18;

/**
 * 커서가 가장자리에 닿았을 때 굴릴 스크롤 양.
 *
 * 가상 스크롤 경계를 넘어 드래그하면 화면이 따라와야 한다(완료 조건 5). 컨테이너 밖으로
 * 나간 경우도 같은 방향으로 계속 민다.
 */
export function autoScrollDelta(
  offsetX: number,
  offsetY: number,
  width: number,
  height: number,
): { dx: number; dy: number } {
  return {
    dx: axisDelta(offsetX, width),
    dy: axisDelta(offsetY, height),
  };
}

function axisDelta(offset: number, size: number): number {
  if (offset < EDGE) {
    return -STEP;
  }
  if (offset > size - EDGE) {
    return STEP;
  }
  return 0;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
