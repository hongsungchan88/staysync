import type { CalendarGrid, ReservationBar } from '@/api/schemas';
import { daysBetween } from '@/lib/dates';

/**
 * 그리드 치수와 막대 쌓기 규칙.
 *
 * 치수를 CSS 변수가 아니라 여기에 둔다. 가상 스크롤은 픽셀 값을 **자바스크립트에서**
 * 계산해야 하고(어느 행이 보이는지, 여백을 얼마나 둘지), CSS 에도 같은 값을 두면 한쪽만
 * 고쳤을 때 스크롤 위치가 조용히 어긋난다. 값 자체는 와이어프레임에서 가져왔다.
 */
export const DAY_W = 66;
export const LEFT_W = 216;
export const HEAD_H = 58;

/** 셀의 잔여 재고·요금·표식이 차지하는 높이. 막대는 이 아래에 쌓인다. */
export const TEXT_H = 36;
export const LANE_H = 24;
export const ROW_PAD = 6;

/**
 * 막대 줄 수의 상한. 넘으면 "+N" 으로 접는다.
 *
 * 상한이 없으면 겹치는 예약이 많은 날 하나 때문에 그 판매 단위의 행 전체가 높아진다.
 * 가상 스크롤이 다뤄야 할 높이 편차도 그만큼 커진다. 작업지시 05 의 5절 3번.
 */
export const MAX_LANES = 3;

/** 한 판매 단위 행의 배치 결과. */
export interface UnitLayout {
  /** 실제로 그릴 줄 수. 자리 수가 아니라 그날 겹치는 예약 수에서 나온다. */
  laneCount: number;
  height: number;
  /** 화면에 그릴 막대. 조회 기간 밖으로 잘린 뒤의 칸 인덱스를 함께 담는다. */
  bars: PlacedBar[];
  /**
   * 접힌 예약. 날짜 칸 인덱스별 목록이다.
   *
   * 막대를 그리지 않은 날에 "+N" 을 띄우기 위한 것이라, 한 예약이 여러 날에 걸치면
   * 그 날들에 모두 들어간다.
   */
  foldedByDay: Map<number, ReservationBar[]>;
}

export interface PlacedBar {
  bar: ReservationBar;
  lane: number;
  /** 그리드 왼쪽 끝에서 몇 번째 칸부터인지. 조회 기간 앞으로 삐져나간 예약은 0 이다. */
  startIndex: number;
  /** 끝나는 칸(미포함). 뒤로 삐져나간 예약은 마지막 칸이다. */
  endIndex: number;
}

/**
 * 겹치는 예약에 줄 번호를 매긴다.
 *
 * 시작일 오름차순으로 훑으며 **비어 있는 가장 낮은 줄**에 넣는다. 이렇게 하면 줄 수가
 * 그날 겹치는 예약 수의 최댓값과 정확히 같아진다(구간 분할의 정석). 자리 수만큼 줄을
 * 미리 나누면 8인실은 예약이 없어도 8줄이 된다.
 *
 * 상한을 넘긴 예약은 줄을 받지 못하고 접힌다. 접힌 예약이 걸친 날에는 "+N" 이 뜨는데,
 * 그 날 실제로 그려지지 않은 예약의 수와 같으므로 표시가 거짓이 되지는 않는다.
 */
export function layoutUnit(
  bars: ReservationBar[],
  gridFrom: string,
  totalDays: number,
): UnitLayout {
  const placed: PlacedBar[] = [];
  const foldedByDay = new Map<number, ReservationBar[]>();

  // 시작일이 같으면 식별자로 가른다. 순서가 흔들리면 다시 그릴 때마다 줄이 바뀐다.
  const sorted = [...bars].sort(
    (a, b) => a.checkIn.localeCompare(b.checkIn) || a.id - b.id,
  );

  // 줄마다 마지막으로 놓인 막대의 끝. 시작일 오름차순이라 이 값만 보면 충분하다.
  const laneEnds: number[] = [];
  let laneCount = 0;

  for (const bar of sorted) {
    const startIndex = Math.max(0, daysBetween(gridFrom, bar.checkIn));
    const endIndex = Math.min(totalDays, daysBetween(gridFrom, bar.checkOut));
    if (endIndex <= startIndex) {
      // 조회 기간과 겹치지 않는다. 백엔드가 경계에 걸친 예약도 함께 주기 때문에 생긴다.
      continue;
    }

    let lane = 0;
    while (lane < MAX_LANES && (laneEnds[lane] ?? 0) > startIndex) {
      lane++;
    }

    if (lane === MAX_LANES) {
      for (let day = startIndex; day < endIndex; day++) {
        const list = foldedByDay.get(day);
        if (list) {
          list.push(bar);
        } else {
          foldedByDay.set(day, [bar]);
        }
      }
      continue;
    }

    laneEnds[lane] = endIndex;
    laneCount = Math.max(laneCount, lane + 1);
    placed.push({ bar, lane, startIndex, endIndex });
  }

  // 예약이 없는 단위도 한 줄은 있어야 한다. 행 높이가 0 이 되면 좌측 목록이 사라진다.
  const lanes = Math.max(1, laneCount);
  return {
    laneCount: lanes,
    height: TEXT_H + lanes * LANE_H + ROW_PAD,
    bars: placed,
    foldedByDay,
  };
}

/** 판매 단위별 배치. 그리드가 행 높이를 알아야 가상 스크롤을 시작할 수 있다. */
export function layoutGrid(data: CalendarGrid): Map<number, UnitLayout> {
  const totalDays = data.units[0]?.days.length ?? 0;

  const barsByUnit = new Map<number, ReservationBar[]>();
  for (const bar of data.reservations) {
    const list = barsByUnit.get(bar.unitId);
    if (list) {
      list.push(bar);
    } else {
      barsByUnit.set(bar.unitId, [bar]);
    }
  }

  const layouts = new Map<number, UnitLayout>();
  for (const unit of data.units) {
    layouts.set(unit.id, layoutUnit(barsByUnit.get(unit.id) ?? [], data.from, totalDays));
  }
  return layouts;
}
