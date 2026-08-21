package com.staysync.channel.port;

/** 채널 어댑터의 종류. */
public enum AdapterType {

    /** RFC 5545 캘린더 교환. 승인 절차 없이 쓸 수 있지만 지연이 크고 날짜만 오간다. */
    ICAL,

    /** 상용 화이트라벨 채널매니저 API. 요금과 제약까지 보낼 수 있다. */
    CHANNEX,

    /** 자체 제작 시뮬레이터. 지연과 실패를 주입해 동기화 로직을 검증한다. */
    MOCK
}
