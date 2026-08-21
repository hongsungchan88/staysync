package com.staysync.channel.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 모든 채널이 따르는 공통 계약.
 *
 * <p>폴링 방식(iCal), 푸시 방식(REST + 웹훅), 시뮬레이터를 하나의 인터페이스 뒤에
 * 감춘다. 도메인 로직은 어떤 채널과 이야기하는지 알 필요가 없다.
 *
 * <p>구현체는 자신이 지원하는 기능만 {@link #capabilities()} 로 알리고,
 * 지원하지 않는 메서드는 기본 구현을 그대로 둔다.
 */
public interface ChannelAdapter {

    AdapterType type();

    Set<Capability> capabilities();

    default boolean supports(Capability capability) {
        return capabilities().contains(capability);
    }

    // --- 우리 → 채널 ----------------------------------------------------

    /**
     * 재고와 요금, 판매 제약을 채널에 반영한다.
     *
     * <p>호출 빈도에 주의한다. Channex 는 숙소당 분당 10회로 제한하므로
     * 변경 사항을 모아 6초 간격으로 한 번씩 보낸다.
     */
    SyncResult pushAri(ChannelCredentials credentials, AriUpdateCommand command);

    /** 우리 캘린더를 채널이 읽을 수 있는 형태로 발행한다. iCal 어댑터만 구현한다. */
    default Optional<String> exportCalendar(ChannelCredentials credentials, Long unitId) {
        return Optional.empty();
    }

    // --- 채널 → 우리 ----------------------------------------------------

    /** 폴링으로 예약을 수집한다. iCal 과 일부 REST 채널이 구현한다. */
    default List<InboundBooking> pullBookings(ChannelCredentials credentials) {
        return List.of();
    }

    /** 웹훅 본문을 표준 예약 형태로 변환한다. */
    default List<InboundBooking> parseWebhook(ChannelCredentials credentials,
                                              String rawBody,
                                              Map<String, String> headers) {
        return List.of();
    }

    /** 웹훅 서명을 검증한다. 검증하지 않는 채널은 기본값 그대로 둔다. */
    default boolean verifySignature(ChannelCredentials credentials,
                                    String rawBody,
                                    Map<String, String> headers) {
        return true;
    }
}
