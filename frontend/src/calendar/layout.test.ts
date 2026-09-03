import { describe, expect, it } from 'vitest';
import type { ReservationBar } from '@/api/schemas';
import { LANE_H, MAX_LANES, ROW_PAD, TEXT_H, layoutUnit } from './layout';

/**
 * 완료 조건 10. 막대 쌓기 규칙(작업지시 05 의 2절 D, 5절 3번).
 *
 * 자리 수가 아니라 **그날 실제로 겹치는 예약 수**만큼 줄을 만든다는 것이 결정이다.
 * 자리 수만큼 미리 나누면 8인실은 예약이 없어도 8줄이 되고, 행 높이가 그만큼 커져
 * 가상 스크롤이 다뤄야 할 편차도 함께 커진다.
 */

const FROM = '2026-09-01';
const DAYS = 30;

function bar(id: number, checkIn: string, checkOut: string): ReservationBar {
  return {
    id,
    unitId: 1,
    checkIn,
    checkOut,
    guestName: `게스트${id}`,
    channel: 'DIRECT',
    status: 'CONFIRMED',
    amount: 100000,
  };
}

/** 줄 수만 놓고 보는 높이. 테스트가 픽셀을 손으로 세지 않게 한다. */
function heightOf(lanes: number): number {
  return TEXT_H + lanes * LANE_H + ROW_PAD;
}

describe('막대 쌓기', () => {
  it('4인 도미토리에 예약이 한 건이면 한 줄이다', () => {
    const layout = layoutUnit([bar(1, '2026-09-03', '2026-09-06')], FROM, DAYS);

    // 자리가 넷이어도 줄은 하나다. 빈 자리 수는 셀의 avail 이 이미 보여 준다.
    expect(layout.laneCount).toBe(1);
    expect(layout.height).toBe(heightOf(1));
    expect(layout.bars).toHaveLength(1);
    expect(layout.foldedByDay.size).toBe(0);
  });

  it('네 건이 겹치면 세 줄과 "+1" 이 된다', () => {
    const bars = [
      bar(1, '2026-09-03', '2026-09-06'),
      bar(2, '2026-09-03', '2026-09-06'),
      bar(3, '2026-09-03', '2026-09-06'),
      bar(4, '2026-09-03', '2026-09-06'),
    ];

    const layout = layoutUnit(bars, FROM, DAYS);

    expect(layout.laneCount).toBe(MAX_LANES);
    expect(layout.height).toBe(heightOf(MAX_LANES));
    expect(layout.bars.map((placed) => placed.lane)).toEqual([0, 1, 2]);

    // 접힌 한 건이 걸친 날마다 "+1" 이 뜬다. 9월 3·4·5일 세 칸이고 체크아웃일은 아니다.
    expect([...layout.foldedByDay.keys()].sort((a, b) => a - b)).toEqual([2, 3, 4]);
    expect(layout.foldedByDay.get(2)).toHaveLength(1);
    expect(layout.foldedByDay.get(2)?.[0]?.id).toBe(4);
  });

  it('겹치지 않는 예약은 여러 건이어도 한 줄에 들어간다', () => {
    const bars = [
      bar(1, '2026-09-03', '2026-09-05'),
      bar(2, '2026-09-05', '2026-09-07'),
      bar(3, '2026-09-07', '2026-09-09'),
    ];

    // 앞 예약의 체크아웃일이 뒤 예약의 체크인일이다. 같은 밤을 쓰지 않으므로 겹치지 않는다.
    const layout = layoutUnit(bars, FROM, DAYS);

    expect(layout.laneCount).toBe(1);
    expect(layout.bars.map((placed) => placed.lane)).toEqual([0, 0, 0]);
  });

  it('예약이 없어도 한 줄 높이는 남는다', () => {
    const layout = layoutUnit([], FROM, DAYS);

    // 0 이 되면 행이 사라져 좌측 목록에서 그 판매 단위를 볼 수 없게 된다.
    expect(layout.laneCount).toBe(1);
    expect(layout.height).toBe(heightOf(1));
  });

  it('조회 기간 밖으로 삐져나간 예약은 잘라서 놓는다', () => {
    const bars = [
      bar(1, '2026-08-28', '2026-09-03'), // 앞으로 삐져나감
      bar(2, '2026-09-28', '2026-10-05'), // 뒤로 삐져나감
      bar(3, '2026-07-01', '2026-07-05'), // 아예 겹치지 않음
    ];

    const layout = layoutUnit(bars, FROM, DAYS);

    expect(layout.bars).toHaveLength(2);
    expect(layout.bars[0]).toMatchObject({ startIndex: 0, endIndex: 2 });
    expect(layout.bars[1]).toMatchObject({ startIndex: 27, endIndex: DAYS });
  });

  it('입력 순서가 달라도 줄 배정이 같다', () => {
    const bars = [
      bar(1, '2026-09-03', '2026-09-08'),
      bar(2, '2026-09-04', '2026-09-09'),
      bar(3, '2026-09-05', '2026-09-10'),
    ];

    // 다시 그릴 때마다 줄이 바뀌면 화면이 이유 없이 흔들린다.
    const forward = layoutUnit(bars, FROM, DAYS);
    const reversed = layoutUnit([...bars].reverse(), FROM, DAYS);

    expect(reversed.bars).toEqual(forward.bars);
    expect(forward.bars.map((placed) => placed.lane)).toEqual([0, 1, 2]);
  });
});
