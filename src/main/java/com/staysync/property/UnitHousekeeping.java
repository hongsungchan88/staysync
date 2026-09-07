package com.staysync.property;

/**
 * property 가 바깥에 공개하는 청소 상태 쓰기 API. P4 15주차에 열었다.
 *
 * <p>{@link UnitCatalog} 와 짝이다. 저쪽은 "읽기 전용 API" 라고 적혀 있고 그 약속을
 * 깨지 않는다 — pricing 의 {@code RateCalendarView}/{@code RateCalendarEditor} 와 같은
 * 갈래다.
 *
 * <p><b>트랜잭션을 스스로 열지 않는다.</b> 전파가 {@code REQUIRED} 라 부르는 쪽의
 * 트랜잭션에 합류한다. 청소 태스크 완료와 판매 단위 상태는 함께 성공하거나 함께
 * 실패해야 한다 — 하나만 반영되면 "청소 끝났는데 더러움" 또는 그 반대가 남고,
 * <b>둘 다 화면에서는 정상으로 보인다</b>(작업지시 12 의 2절 C).
 *
 * <p>상태 넷 가운데 둘만 연다. {@code INSPECTING} 과 {@code BLOCKED} 는 부르는 곳이
 * 없다 — 쓰지 않는 통로를 미리 열어 두면 어느 쪽이 실제로 도는지 알 수 없어진다.
 */
public interface UnitHousekeeping {

    /** 체크아웃했다. 청소가 필요한 상태로 만든다. */
    void markDirty(Long unitId);

    /** 청소가 끝났다. */
    void markClean(Long unitId);
}
