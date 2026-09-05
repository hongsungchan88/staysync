import { describe, expect, it } from 'vitest';
import { calendarGridSchema, propertySummarySchema } from './schemas';

/**
 * 서버가 `non_null` 로 직렬화한다는 것이 스키마의 전제다.
 *
 * `spring.jackson.default-property-inclusion: non_null` 이라 값이 없는 필드는 `null` 로
 * 오는 것이 아니라 **키가 아예 빠진다.** `z.nullable()` 은 `null` 은 받아도 없는 키는
 * 거절하므로, 주소 없는 숙소 하나가 화면 전체를 오류로 만든다. 파싱이 통째로 실패하기
 * 때문이다. 실제로 P3 10주차 브라우저 확인에서 그렇게 잡혔다.
 *
 * 필드 하나가 늘 때마다 같은 실수를 할 수 있어서 테스트로 고정한다.
 */
describe('응답 스키마', () => {
  it('주소가 빠진 숙소를 받아들인다', () => {
    const parsed = propertySummarySchema.parse({
      id: 1,
      name: '성수동 오피스텔',
      timezone: 'Asia/Seoul',
      currency: 'KRW',
      checkInTime: '15:00',
      checkOutTime: '11:00',
      status: 'ACTIVE',
    });

    expect(parsed.name).toBe('성수동 오피스텔');
  });

  it('게스트 이름이 빠진 예약 막대를 받아들인다', () => {
    const parsed = calendarGridSchema.parse({
      from: '2027-03-01',
      to: '2027-03-02',
      units: [],
      reservations: [
        {
          id: 1,
          unitId: 1,
          checkIn: '2027-03-01',
          checkOut: '2027-03-02',
          channel: 'AIRBNB_ICAL',
          status: 'CONFIRMED',
          amount: 120000,
        },
      ],
    });

    // iCal 수신 예약에는 게스트 이름이 없다(조사-02). P3 에서 실제로 이 모양이 온다.
    expect(parsed.reservations[0]?.guestName).toBeUndefined();
  });
});
