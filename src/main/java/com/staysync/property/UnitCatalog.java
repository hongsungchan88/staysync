package com.staysync.property;

import java.util.List;

/**
 * property 모듈이 바깥에 공개하는 읽기 전용 API.
 *
 * <p>Spring Modulith 규칙상 모듈 최상위 패키지의 타입만 다른 모듈이 참조할 수 있다.
 * {@code property.domain} 아래의 엔티티는 모듈 내부 구현이므로 밖에서 쓸 수 없다.
 *
 * <p>booking 모듈이 재고 원장을 만들 때 판매 수량을 알아야 하는데, 엔티티를 직접
 * 넘기는 대신 필요한 값만 이 인터페이스로 노출한다.
 */
public interface UnitCatalog {

    /** 해당 판매 단위가 동시에 팔 수 있는 수량. 독채는 1, 4인 도미토리는 4. */
    short totalUnitsOf(Long unitId);

    /** 숙소에 속한 판매 단위 식별자 목록. 캘린더 조회에서 쓴다. */
    List<Long> unitIdsOf(Long propertyId);

    /** 판매 단위의 표시 이름. */
    String nameOf(Long unitId);

    /**
     * 숙소의 표시 이름.
     *
     * <p>P4 14주차에 더했다. 메시지 템플릿의 {@code {{propertyName}}} 이 쓴다.
     * 숙소 하나를 위해 인터페이스를 새로 만들지 않고 여기에 뒀다 — 이 타입이 이미
     * "property 모듈이 바깥에 공개하는 읽기 전용 API" 이고, 다른 모듈이 property 에
     * 물을 것이 이름 하나뿐이다.
     */
    String propertyNameOf(Long propertyId);

    /**
     * 판매 단위 하나의 요약.
     *
     * <p>{@link #summariesOf} 는 숙소를 알아야 부를 수 있다. 채널 매핑처럼 판매 단위
     * 식별자만 들고 있는 쪽을 위해 하나짜리 통로를 연다. 수량·기본 요금제·숙소를 한
     * 번에 주므로, 필요한 값마다 따로 묻느라 왕복이 늘지 않는다.
     */
    UnitSummary summaryOf(Long unitId);

    /**
     * 숙소의 판매 단위를 화면 정렬 순서로 돌려준다.
     *
     * <p>캘린더 그리드가 쓴다. {@link #unitIdsOf} 로 식별자만 받고 이름과 수량을 다시
     * 물으면 판매 단위 수만큼 왕복이 늘어난다. 그리드는 한 번에 전부 필요하므로
     * 요약을 통째로 넘긴다.
     */
    List<UnitSummary> summariesOf(Long propertyId);
}
