import { describe, expect, it } from 'vitest';
import { DAY_W, HEAD_H, LEFT_W } from './layout';
import { EDGE, autoScrollDelta, cellAt, rectContains, rectOf, rowOffsetsOf } from './selection';

/**
 * 완료 조건 4·5. 기간 선택의 좌표 계산.
 *
 * 커서 아래의 셀을 찾는 대신 좌표로 계산하는 이유가 여기에 있다 — 가상 스크롤 때문에 화면
 * 밖 칸은 DOM 에 없고, 자동 스크롤이 도는 동안에는 커서가 컨테이너 밖이라 그 아래에 아무
 * 요소도 없다. 두 경우 모두 답이 나와야 선택이 끊기지 않는다.
 */

// 행 높이가 제각각인 상태를 전제로 둔다. D 의 쌓기 규칙 때문에 실제로 그렇다.
const HEIGHTS = [66, 90, 114, 66];
const OFFSETS = rowOffsetsOf(HEIGHTS);
const TOTAL_DAYS = 90;

describe('선택 좌표 계산', () => {
  it('스크롤이 0 이면 첫 칸이 첫 판매 단위의 첫 날이다', () => {
    const cell = cellAt(
      { offsetX: LEFT_W + 1, offsetY: HEAD_H + 1, scrollLeft: 0, scrollTop: 0 },
      OFFSETS,
      TOTAL_DAYS,
    );

    expect(cell).toEqual({ unitIndex: 0, dateIndex: 0 });
  });

  it('스크롤한 만큼 칸이 밀린다', () => {
    const cell = cellAt(
      {
        offsetX: LEFT_W + 1,
        offsetY: HEAD_H + 1,
        scrollLeft: 10 * DAY_W,
        // 첫 두 행(66 + 90)을 지나 세 번째 행에 걸친다
        scrollTop: 66 + 90 + 5,
      },
      OFFSETS,
      TOTAL_DAYS,
    );

    expect(cell).toEqual({ unitIndex: 2, dateIndex: 10 });
  });

  it('행 높이가 제각각이어도 맞는 판매 단위를 고른다', () => {
    // 나눗셈 한 번으로 구하면 여기서 어긋난다. 높이가 66 고정이 아니기 때문이다.
    const at = (y: number) =>
      cellAt({ offsetX: LEFT_W, offsetY: HEAD_H + y, scrollLeft: 0, scrollTop: 0 }, OFFSETS, TOTAL_DAYS)
        .unitIndex;

    expect(at(0)).toBe(0);
    expect(at(65)).toBe(0);
    expect(at(66)).toBe(1);
    expect(at(155)).toBe(1);
    expect(at(156)).toBe(2);
    expect(at(269)).toBe(2);
    expect(at(270)).toBe(3);
  });

  it('컨테이너 밖으로 나가면 가장자리 칸으로 접는다', () => {
    // 자동 스크롤이 도는 동안에는 이게 정상 상태다. 여기서 범위를 벗어난 인덱스를 내면
    // 선택 사각형이 엉뚱하게 커진다.
    const before = cellAt(
      { offsetX: -300, offsetY: -300, scrollLeft: 0, scrollTop: 0 },
      OFFSETS,
      TOTAL_DAYS,
    );
    expect(before).toEqual({ unitIndex: 0, dateIndex: 0 });

    const after = cellAt(
      { offsetX: 99_999, offsetY: 99_999, scrollLeft: 0, scrollTop: 0 },
      OFFSETS,
      TOTAL_DAYS,
    );
    expect(after).toEqual({ unitIndex: HEIGHTS.length - 1, dateIndex: TOTAL_DAYS - 1 });
  });

  it('어느 방향으로 끌어도 같은 사각형이 된다', () => {
    const anchor = { unitIndex: 3, dateIndex: 20 };
    const head = { unitIndex: 1, dateIndex: 5 };

    // 위로 왼쪽으로 끄는 것도 정상 조작이다.
    expect(rectOf(anchor, head)).toEqual({ fromUnit: 1, toUnit: 3, fromDate: 5, toDate: 20 });
    expect(rectOf(head, anchor)).toEqual(rectOf(anchor, head));
  });

  it('사각형은 여러 판매 단위에 걸친다', () => {
    const rect = rectOf({ unitIndex: 0, dateIndex: 3 }, { unitIndex: 2, dateIndex: 5 });

    expect(rectContains(rect, 1, 4)).toBe(true);
    expect(rectContains(rect, 2, 5)).toBe(true);
    expect(rectContains(rect, 3, 4)).toBe(false);
    expect(rectContains(rect, 1, 6)).toBe(false);
  });
});

describe('자동 스크롤', () => {
  it('가장자리에 닿으면 그 방향으로 민다', () => {
    const width = 800;
    const height = 600;

    expect(autoScrollDelta(width / 2, height / 2, width, height)).toEqual({ dx: 0, dy: 0 });
    expect(autoScrollDelta(EDGE - 1, height / 2, width, height).dx).toBeLessThan(0);
    expect(autoScrollDelta(width - EDGE + 1, height / 2, width, height).dx).toBeGreaterThan(0);
    expect(autoScrollDelta(width / 2, EDGE - 1, width, height).dy).toBeLessThan(0);
    expect(autoScrollDelta(width / 2, height - EDGE + 1, width, height).dy).toBeGreaterThan(0);
  });

  it('컨테이너 밖으로 나가도 계속 민다', () => {
    // 커서가 화면 밖으로 나가면 멈추는 구현이 흔한데, 그러면 가장자리에서 손을 조금만
    // 더 움직여도 스크롤이 끊긴다(완료 조건 5).
    const { dx, dy } = autoScrollDelta(-120, 900, 800, 600);

    expect(dx).toBeLessThan(0);
    expect(dy).toBeGreaterThan(0);
  });
});
