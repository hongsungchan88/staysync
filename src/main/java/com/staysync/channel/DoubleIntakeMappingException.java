package com.staysync.channel;

import com.staysync.channel.port.AdapterType;
import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 같은 판매 단위를 iCal 연결과 Channex 연결에 함께 매핑하려 했다(작업지시-17 D).
 *
 * <p>에어비앤비가 Channex 를 거치면 같은 예약이 iCal 발행물로도 온다. 채널 코드가
 * 달라 {@code uq_channel_booking} 이 못 막고, 재고를 두 번 깎아 초과 판매 충돌로 뜬다
 * (조사-04 5절 3번). 한 판매 단위의 수신 입구는 둘 중 하나다.
 */
public class DoubleIntakeMappingException extends DomainException {

    public DoubleIntakeMappingException(Long unitId, AdapterType existing) {
        super("MAPPING_DOUBLE_INTAKE",
                "이 판매 단위는 이미 " + label(existing) + " 연결에 매핑돼 있습니다. "
                        + "iCal 과 Channex 에 함께 매핑하면 같은 예약이 두 번 들어옵니다. unitId=" + unitId);
    }

    private static String label(AdapterType type) {
        return type == AdapterType.ICAL ? "iCal" : "Channex";
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
