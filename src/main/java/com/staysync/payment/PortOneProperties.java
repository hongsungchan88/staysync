package com.staysync.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 포트원 V2 설정. {@code staysync.portone.*} 에 대응한다.
 *
 * <h2>넷을 두 짝으로 나눠 다룬다</h2>
 *
 * <p>{@code storeId} 와 {@code channelKey} 는 <b>브라우저의 결제창 SDK 로 나가는
 * 값이다.</b> 비밀이 아니고, 위젯에 내려보내야 결제창이 열린다.
 *
 * <p>{@code apiSecret} 과 {@code webhookSecret} 은 <b>서버 밖으로 나가지 않는다.</b>
 * 앞의 둘을 내려보내는 응답에 이 둘이 섞이면 테스트 키라도 그 구조가 그대로 굳는다.
 * 그래서 위젯에 내려보내는 값을 {@link #publicConfig()} 하나로 좁혀 두고, 컨트롤러가
 * 이 레코드를 통째로 직렬화할 길을 막았다.
 *
 * <h2>값이 없어도 기동은 된다</h2>
 *
 * <p>{@code JWT_SECRET} 이나 {@code GUEST_DATA_KEY} 와 다르다. 그 둘은 없으면 인증과
 * 개인정보가 통째로 깨지므로 기동을 막는 것이 맞지만, 결제는 <b>이 프로젝트에서
 * 테스트 모드 부가 기능</b>이고 값이 없어도 나머지 기능은 전부 정상이다. 개발자가
 * 결제를 건드리지 않는 주에 키를 요구하면 그게 더 번거롭다.
 *
 * <p>대신 <b>결제를 실제로 쓰려는 순간에 막는다.</b> {@link #requireConfigured()} 와
 * {@link #requireWebhookSecret()} 이 그 자리이고, 설정이 비었는데 결제창을 열거나
 * 웹훅을 처리하려 하면 거기서 실패한다. <b>검증 없이 통과시키지 않는다.</b>
 */
@ConfigurationProperties(prefix = "staysync.portone")
public record PortOneProperties(
        String storeId,
        String channelKey,
        String apiSecret,
        String webhookSecret,
        String apiBase) {

    /** 포트원 V2 API 주소. 설정에서 비워 두면 이 값을 쓴다. */
    public static final String DEFAULT_API_BASE = "https://api.portone.io";

    public PortOneProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = DEFAULT_API_BASE;
        }
    }

    /**
     * 위젯에 내려보내도 되는 값만.
     *
     * <p><b>이 메서드가 공개 경계다.</b> 컨트롤러가 이 레코드를 직접 돌려주지 않고
     * 반드시 이걸 거치게 해서, 나중에 비밀 값을 하나 더 넣더라도 응답에 새지 않는다.
     */
    public PublicConfig publicConfig() {
        return new PublicConfig(storeId, channelKey);
    }

    /** 결제창 SDK 가 필요로 하는 값. 둘 다 비밀이 아니다. */
    public record PublicConfig(String storeId, String channelKey) {
    }

    /** 결제창을 열거나 결제사에 물어보기 전에 부른다. */
    void requireConfigured() {
        require(storeId, "PORTONE_STORE_ID");
        require(channelKey, "PORTONE_CHANNEL_KEY");
        require(apiSecret, "PORTONE_API_SECRET");
    }

    /**
     * 웹훅을 처리하기 전에 부른다.
     *
     * <p><b>시크릿이 없으면 웹훅을 처리하지 않는다.</b> 검증을 건너뛰고 받아 주면
     * 아무나 결제 완료 웹훅을 보내 예약을 확정시킬 수 있다. 값이 없을 때 조용히
     * 통과시키는 것이 이 기능에서 가장 위험한 실패 모드다.
     */
    void requireWebhookSecret() {
        require(webhookSecret, "PORTONE_WEBHOOK_SECRET");
    }

    private static void require(String value, String envName) {
        if (value == null || value.isBlank()) {
            throw new PortOneNotConfiguredException(envName);
        }
    }
}
