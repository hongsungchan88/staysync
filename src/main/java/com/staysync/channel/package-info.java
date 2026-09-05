/**
 * 채널 연결과 동기화를 다룬다. P3 10~13주차에 구현한다.
 *
 * <p>{@code channel.port} 의 인터페이스가 이 모듈의 계약이고, {@code channel.adapter}
 * 아래에 iCal, Channex, Mock 구현이 들어간다. 도메인 로직은 어떤 채널과 이야기하는지
 * 알 필요가 없다.
 *
 * <p>10주차에 레지스트리와 연결·매핑이 찼다. <b>어댑터 구현은 아직 0개다</b> —
 * Mock 12주차, Channex 12~13주차, iCal 13주차. 동기화 워커와 {@code sync_job} 은
 * 12주차다.
 *
 * <p>11주차에 만든 것은 어댑터가 아니라 <b>상대역</b>이다. 저장소 루트의
 * {@code mock-ota-simulator/} 가 채널 노릇을 하는 별도 애플리케이션이고, 12주차의
 * {@code MockOtaAdapter} 가 그것을 두드린다(ADR 0011).
 *
 * <p>판매 단위는 {@code property.UnitCatalog} 로만 받는다. 의존 방향은
 * channel → property 다.
 */
package com.staysync.channel;
