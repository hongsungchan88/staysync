package com.staysync.channel.port;

import java.util.List;
import java.util.Map;
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

    /**
     * 채널이 지금 들고 있는 값을 날짜별로 읽어 온다. 정기 재동기화(계획서 6.6)가 쓴다.
     *
     * <p>{@link Capability#PUSH_AVAILABILITY} 를 선언한 어댑터만 호출된다. 보낼 수
     * 없는 채널은 대조할 것도 없다 — iCal 이 그렇다.
     *
     * <p>돌려주는 목록에 없는 날짜는 "채널이 그 날짜를 모른다"는 뜻이고, 대조는 그걸
     * 차이로 본다. 우리가 보낸 적이 있는데 채널에 없으면 유실된 것이기 때문이다.
     */
    default List<ChannelAriDay> fetchAriSnapshot(ChannelCredentials credentials,
                                                 String externalUnitId,
                                                 java.time.LocalDate from,
                                                 java.time.LocalDate to) {
        throw new UnsupportedOperationException(
                type() + " 는 재고·요금 조회를 지원하지 않습니다.");
    }

    // --- 채널 → 우리 ----------------------------------------------------

    /**
     * 폴링으로 예약을 수집한다. iCal 과 일부 REST 채널이 구현한다.
     *
     * <p>{@code knownEtag} 는 직전 응답의 {@code ETag} 다. 조건부 요청을 지원하는
     * 채널은 이걸 실어 보내고 304 를 받으면 {@link BookingFeed#unchanged()} 를
     * 돌려준다. 지원하지 않는 채널은 무시하면 된다.
     *
     * <p>{@link Capability#PULL_BOOKING} 을 선언한 어댑터만 호출된다. 선언하지 않고
     * 구현하면 폴링이 그 채널을 조용히 건너뛰고, 선언하고 구현하지 않으면 예약이 하나도
     * 들어오지 않는다. 둘 다 로그에 아무것도 남기지 않아
     * {@code AdapterContractTest} 가 그 어긋남을 잡는다.
     */
    default BookingFeed pullBookings(ChannelCredentials credentials, String knownEtag) {
        throw new UnsupportedOperationException(
                type() + " 는 예약 폴링을 지원하지 않습니다.");
    }

    /**
     * 웹훅 본문을 표준 예약 형태로 변환한다.
     *
     * <p>수신 엔드포인트는 아직 없다. 12주차에 폴링으로 정했고 13주차에도 그대로다
     * (작업지시 10 의 3절). {@link Capability#WEBHOOK_BOOKING} 을 선언한 어댑터가
     * 생기면 그때 부르는 쪽이 붙는다.
     */
    default List<InboundBooking> parseWebhook(ChannelCredentials credentials,
                                              String rawBody,
                                              Map<String, String> headers) {
        throw new UnsupportedOperationException(
                type() + " 는 웹훅 수신을 지원하지 않습니다.");
    }

    // --- 메시징 (P4 14주차) -----------------------------------------------

    /**
     * 게스트 메시지를 긁어 온다.
     *
     * <p>{@link Capability#MESSAGING} 을 선언한 어댑터만 호출된다. iCal 은 메시징이
     * 없고 그것이 정상이다 — 눈으로는 "없는 게 맞는 것"과 "빠뜨린 것"이 구분되지
     * 않으므로 {@code AdapterContractTest} 가 둘을 가른다.
     *
     * <p>같은 메시지가 두 번 올 수 있다. 거르는 것은 부르는 쪽의 일이다.
     */
    default List<com.staysync.channel.InboundChannelMessage> pullMessages(
            ChannelCredentials credentials) {
        throw new UnsupportedOperationException(type() + " 는 메시지 수집을 지원하지 않습니다.");
    }

    /**
     * 메시지를 채널로 보낸다. <b>보낸 뒤에는 되돌릴 수 없다.</b>
     *
     * <p>{@code sync_job} 워커만 이 메서드를 부른다. 화면이나 자동 발송이 직접 부르면
     * 12주차의 재시도·백오프·{@code DEAD} 를 통째로 우회하게 되고, 실패한 발송이
     * 조용히 사라진다.
     */
    default SyncResult sendMessage(ChannelCredentials credentials,
                                   com.staysync.channel.OutboundChannelMessage message) {
        throw new UnsupportedOperationException(type() + " 는 메시지 발송을 지원하지 않습니다.");
    }

    /** 웹훅 서명을 검증한다. 검증하지 않는 채널은 기본값 그대로 둔다. */
    default boolean verifySignature(ChannelCredentials credentials,
                                    String rawBody,
                                    Map<String, String> headers) {
        return true;
    }
}
