/**
 * 예약과 재고를 다룬다.
 *
 * <p>중복예약 방지가 이 모듈의 핵심 책임이다. 재고 원장({@code InventoryLedger})이
 * 기준 데이터이며, 네 계층의 방어 구조는 {@code InventoryService} 문서를 참고한다.
 *
 * <p>property 모듈의 공개 API({@code UnitCatalog})만 참조하고, 그 안의 엔티티는
 * 직접 쓰지 않는다.
 */
package com.staysync.booking;
