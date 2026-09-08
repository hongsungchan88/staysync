/**
 * 운영 리포트와 지표. P4 16주차에 구현했다.
 *
 * <p>계획서 8.8 의 지표 여덟 중 <b>여섯</b>을 만든다 — 점유율, ADR, RevPAR,
 * 채널 믹스, 리드타임, 취소율. 빠진 둘의 사유는 {@link com.staysync.analytics.ReportMetrics}
 * 주석에 있다. <b>못 만든 것을 만든 것처럼 적지 않는다.</b>
 *
 * <p>의존 방향은 analytics → property 다. 예약과 박 행은 SQL 로 직접 읽는다 —
 * 여기서 필요한 것은 행이 아니라 합계이고, booking 의 조회 포트는 행을 돌려준다.
 */
package com.staysync.analytics;
