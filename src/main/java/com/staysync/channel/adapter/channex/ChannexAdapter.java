package com.staysync.channel.adapter.channex;

import com.staysync.channel.port.AdapterType;
import com.staysync.channel.port.AriUpdateCommand;
import com.staysync.channel.port.Capability;
import com.staysync.channel.port.ChannelAdapter;
import com.staysync.channel.port.ChannelCredentials;
import com.staysync.channel.port.SyncResult;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Channex 를 두드리는 어댑터. <b>레지스트리의 세 번째 등록자다</b>(작업지시-17).
 *
 * <p>P3 의 "세 구현을 감춘다"가 실제가 되는 자리다. 브랜치 셋으로 나눠 붙인다 —
 * 연결·매핑(이 브랜치), 재고·요금 전송, 예약 피드. <b>선언은 구현된 것만 한다</b>
 * (2절 E, 12~14주차에 선언과 구현이 어긋난 결함이 세 번). 지금은 아무 기능도 선언하지
 * 않으므로 폴러와 전파는 이 종류의 연결을 건너뛰고, 화면은 전부 "미지원"으로 보여 준다.
 * 다음 브랜치가 {@link AdapterType#CHANNEX} 의 선언과 이 클래스를 함께 채운다.
 *
 * <p>자격 증명은 둘이다 — {@link #API_KEY}(비밀, {@code user-api-key} 헤더)와
 * {@link #PROPERTY_ID}(Channex 숙소 식별자, 비밀 아님). 둘 다 연결을 만들 때
 * {@code ChannelConnectionService} 가 있는지 본다. 주소는 연결이 아니라 설정이다
 * ({@code staysync.channel.channex.base-url}, 기본 스테이징) — 스테이징/운영은 배포
 * 단위로 갈리지 연결마다 갈리지 않는다.
 *
 * <p><b>API 키는 어디에도 찍지 않는다.</b> 예외 메시지에 요청 헤더나 URL 이 실리지
 * 않는지 본다(4절 — iCal 주소가 실리던 {@code a7c9df2} 와 같은 자리).
 */
@Component
public class ChannexAdapter implements ChannelAdapter {

    /** 자격 증명 키. 값은 {@code user-api-key} 헤더로 나간다. <b>비밀이다.</b> */
    public static final String API_KEY = "api_key";

    /** 자격 증명 키. Channex 쪽 숙소 식별자(UUID). 비밀은 아니지만 연결의 일부다. */
    public static final String PROPERTY_ID = "property_id";

    private final String baseUrl;

    ChannexAdapter(@Value("${staysync.channel.channex.base-url:https://staging.channex.io}")
                   String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public AdapterType type() {
        return AdapterType.CHANNEX;
    }

    @Override
    public Set<Capability> capabilities() {
        return type().capabilities();
    }

    /**
     * 아직 없다. {@code PUSH_*} 를 선언하지 않았으므로 워커가 부르지 않고, 부르면
     * {@code AdapterContractTest} 의 "선언하지 않은 기능은 실패한다"가 원하는 그 예외다.
     */
    @Override
    public SyncResult pushAri(ChannelCredentials credentials, AriUpdateCommand command) {
        throw new UnsupportedOperationException(type() + " 는 아직 재고·요금 전송을 지원하지 않습니다.");
    }

    String baseUrl() {
        return baseUrl;
    }
}
