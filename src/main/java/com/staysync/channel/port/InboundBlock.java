package com.staysync.channel.port;

import java.time.LocalDate;

/**
 * 채널이 준 <b>예약이 아닌</b> 일정. 에어비앤비 게시 리스팅의 {@code Airbnb (Not available)} 가 첫 사례다.
 *
 * <p>P5 17주차에는 <b>세기만 한다.</b> 재고에 반영하지 않는다 — 진짜 호스트 차단 샘플이
 * 없어(업체 피드 셋에서 본 것은 전부 1년 창의 끝 하루였다) 차단 표현을 아직 짓지 않기로
 * 했다. 예약으로 넣으면 게스트 없음·0원 예약이 리포트의 ADR 을 낮추고 점유율을 올린다.
 *
 * @param endExclusive {@code DTEND} 그대로. 배타적이다
 */
public record InboundBlock(String id, LocalDate start, LocalDate endExclusive, String label) {
}
