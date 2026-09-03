/**
 * 요금 캘린더와 판매 제약, 요금 규칙 엔진을 다룬다.
 *
 * <p>바깥에 공개하는 것은 최상위 패키지의 타입뿐이다. {@code pricing.domain} 아래의
 * 엔티티는 모듈 내부 구현이라 다른 모듈이 참조하면 {@code ModularityTest} 가 깨진다.
 *
 * <ul>
 *   <li>{@link com.staysync.pricing.RateCalendarView} — 읽기(P2 7주차)</li>
 *   <li>{@link com.staysync.pricing.RateCalendarEditor} — 쓰기(P2 9주차)</li>
 *   <li>{@link com.staysync.pricing.RateEngine} 과 {@link com.staysync.pricing.RateRule}
 *       — 요금 규칙 계산(P2 9주차)</li>
 * </ul>
 *
 * <p><b>요금·제약 일괄 편집은 여기 없다.</b> {@code booking.calendar} 에 있다.
 * 일괄 편집은 요금(pricing)과 판매중지(booking)를 한 번에 바꾸므로, pricing 에 두면
 * pricing → booking 참조가 생기고 7주차 조립부가 만든 booking → pricing 과 맞물려
 * 순환이 된다. 근거는 작업지시 06 의 5절 1번.
 *
 * <p><b>규칙을 저장하지 않는다.</b> {@link com.staysync.pricing.RateRule} 을 담는
 * 테이블도 CRUD 도 없다. 저장된 규칙을 읽는 곳이 아직 없기 때문이다 — 자동 적용은
 * 계획서 8.6 의 운영 자동화와 P5 의 AI 요금 추천이고, 지금 만들면 아무도 안 쓰는
 * 테이블이 하나 생긴다. 5~6주차에 소비자 워커 셋을 만들지 않은 것과 같은 판단이다.
 */
package com.staysync.pricing;
