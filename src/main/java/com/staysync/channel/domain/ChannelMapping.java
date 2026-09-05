package com.staysync.channel.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 우리 판매 단위와 채널 쪽 객실 식별자를 잇는다.
 *
 * <p>채널마다 식별자의 모양이 다르다. iCal 은 리스팅 하나(내보내기 URL 이 곧 식별자),
 * Channex 는 {@code property_id → room_type_id → rate_plan_id} 3계층이다.
 * <b>그 차이를 이 테이블이 흡수한다.</b> 도메인은 채널 쪽 계층을 모르고 문자열
 * 식별자로만 다룬다. 해석은 어댑터의 몫이다.
 *
 * <p>한 판매 단위가 채널 여럿에 매핑되는 것이 이 제품의 존재 이유다. 반대로
 * <b>같은 연결 안에서 한 판매 단위가 두 번 매핑되면 안 된다</b> — 같은 재고를 두 번
 * 보내게 되고, 증상은 12주차 워커가 돌기 시작해야 나온다. V3 의 유니크 인덱스가 막는다.
 */
@Entity
@Table(name = "channel_mapping")
public class ChannelMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(name = "external_unit_id", nullable = false, length = 120)
    private String externalUnitId;

    /** Channex 의 {@code rate_plan_id} 처럼 요금 계층이 따로 있는 채널만 채운다. */
    @Column(name = "external_rate_id", length = 120)
    private String externalRateId;

    /**
     * iCal 발행 URL 의 토큰. {@code GET /public/ical/{token}.ics} 가 이 값으로 찾는다.
     *
     * <p><b>URL 자체가 인증이다</b>(계획서 6.2). 그래서 추측 불가능한 난수여야 하고,
     * 목록·상세 응답과 로그에 실리면 안 된다. 꺼내 보는 경로는 전용 조회 하나다.
     *
     * <p>매핑마다 하나인 이유는 에어비앤비의 "다른 웹사이트에 연결하기"가 리스팅
     * 하나에 {@code .ics} 주소 하나를 받기 때문이다(조사-02 1절).
     */
    @Column(name = "export_token", length = 64)
    private String exportToken;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ChannelMapping() {
    }

    public ChannelMapping(Long connectionId, Long unitId, String externalUnitId,
                          String externalRateId, String exportToken) {
        this.connectionId = connectionId;
        this.unitId = unitId;
        this.externalUnitId = externalUnitId;
        this.externalRateId = externalRateId;
        this.exportToken = exportToken;
    }

    public Long getId() {
        return id;
    }

    public Long getConnectionId() {
        return connectionId;
    }

    public Long getUnitId() {
        return unitId;
    }

    public String getExternalUnitId() {
        return externalUnitId;
    }

    public String getExternalRateId() {
        return externalRateId;
    }

    /** <b>응답과 로그에 담지 않는다.</b> 전용 조회 경로만 이 값을 꺼낸다. */
    public String getExportToken() {
        return exportToken;
    }
}
