package com.staysync.property.domain;

/**
 * 판매 단위의 유형. 에어비앤비의 숙소 유형 구분과 대응시켰다.
 * 채널 매핑과 요금 추천의 입력으로 쓴다.
 */
public enum UnitKind {

    /** 공간 전체를 통째로 대여한다. 오피스텔 한 채, 독채 등. */
    ENTIRE_PLACE,

    /** 집의 방 하나를 대여하고 거실·주방은 공유한다. */
    PRIVATE_ROOM,

    /** 도미토리처럼 같은 방의 자리를 여러 명에게 판다. */
    SHARED_ROOM
}
