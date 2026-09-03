package com.staysync.pricing;

import java.util.List;

/**
 * pricing 이 바깥에 공개하는 쓰기 API. P2 9주차에 열었다.
 *
 * <p>{@link RateCalendarView} 와 짝이다. 조립부는 이 인터페이스만 참조하고
 * {@code pricing.domain} 을 보지 않는다.
 *
 * <p><b>트랜잭션을 스스로 열지 않는다.</b> 전파가 {@code REQUIRED} 라 부르는 쪽의
 * 트랜잭션에 합류한다. 일괄 편집은 요금과 판매중지를 함께 바꾸는데 둘이 다른 트랜잭션이면
 * 절반만 반영된 화면이 남는다. 그 화면은 정상으로 보이고, 어긋난 것은 나중에 드러난다.
 */
public interface RateCalendarEditor {

    /**
     * 셀들을 주어진 값으로 만든다. 행이 없으면 만들고 있으면 덮어쓴다.
     *
     * @return 실제로 쓴 셀 수
     */
    int applyAll(List<RateChange> changes);
}
