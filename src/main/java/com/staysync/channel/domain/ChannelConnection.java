package com.staysync.channel.domain;

import com.staysync.channel.port.AdapterType;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 숙소 하나에 붙은 채널 연결. 어댑터 종류 하나와 자격 증명 한 벌을 갖는다.
 *
 * <p><b>자격 증명은 값만 암호화해 담는다.</b> {@code {"api_key":"<Base64 암호문>"}}
 * 형태다. 키 이름은 비밀이 아니고, 키까지 암호화하면 어떤 설정이 들어 있는지 보려고
 * 매번 복호화해야 한다. 값은 유출되면 그 사람의 채널 계정 전체가 열리므로 평문으로
 * 두지 않는다(ADR 0007 의 두 번째 적용).
 *
 * <p>{@code etag} 와 {@code last_event_count} 는 P3 13주차에 매핑했다. iCal 폴링의
 * 조건부 요청과 대량 소실 방어가 쓴다. <b>어댑터가 아니라 여기에 있어야 한다</b> —
 * 어댑터가 메모리에 들고 있으면 재기동 직후 첫 폴링에 기준값이 없고, 그 순간이 정확히
 * 방어가 필요한 순간이다.
 *
 * <p>{@code last_sync_*} 는 아직 매핑하지 않았다. 읽는 곳이 없다.
 */
@Entity
@Table(name = "channel_connection")
public class ChannelConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "channel_code", nullable = false, length = 40)
    private String channelCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "adapter_type", nullable = false, length = 20)
    private AdapterType adapterType;

    @Column(name = "display_name", length = 100)
    private String displayName;

    /** 값만 암호화한 JSON. 절대 응답이나 로그로 내보내지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "credentials")
    private String credentials;

    @Column(name = "sync_enabled", nullable = false)
    private boolean syncEnabled = true;

    /** 직전 iCal 응답의 {@code ETag}. 다음 요청의 {@code If-None-Match} 에 실린다. */
    @Column(name = "etag", length = 200)
    private String etag;

    /**
     * 직전 발행물의 이벤트 수. 대량 소실 방어의 기준값이다(계획서 13.4).
     *
     * <p>{@code null} 은 "아직 한 번도 받지 못했다"는 뜻이고, 그때는 비교할 것이
     * 없으므로 방어가 걸리지 않는다.
     */
    @Column(name = "last_event_count")
    private Integer lastEventCount;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ChannelConnection() {
    }

    public ChannelConnection(Long propertyId, String channelCode, AdapterType adapterType,
                             String displayName, String credentials) {
        this.propertyId = propertyId;
        this.channelCode = channelCode;
        this.adapterType = adapterType;
        this.displayName = displayName;
        this.credentials = credentials;
    }

    public Long getId() {
        return id;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public String getChannelCode() {
        return channelCode;
    }

    public AdapterType getAdapterType() {
        return adapterType;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 암호화된 JSON 그대로. 복호화는 {@code ChannelCredentialStore} 한 곳에서만 한다. */
    public String getCredentials() {
        return credentials;
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public String getEtag() {
        return etag;
    }

    public Integer getLastEventCount() {
        return lastEventCount;
    }

    /**
     * 폴링 한 번의 결과를 기록한다.
     *
     * <p>대량 소실 방어가 걸린 주기에는 <b>부르지 않는다.</b> 부르면 줄어든 수가
     * 다음 주기의 기준값이 되어, 한 번 더 줄어들 때는 방어가 걸리지 않는다.
     * 그렇게 두 번이면 전체가 취소된다.
     */
    public void recordFeed(String etag, int eventCount) {
        if (etag != null && !etag.isBlank()) {
            this.etag = etag;
        }
        this.lastEventCount = eventCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    public void changeSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    /**
     * 자격 증명을 통째로 바꾼다.
     *
     * <p>부르는 쪽이 이미 "빈 값이면 기존 값 유지"를 적용한 결과를 넘긴다. 여기서
     * 병합하지 않는 것은, 병합하려면 엔티티가 복호화를 알아야 하기 때문이다.
     */
    public void replaceCredentials(String credentials) {
        this.credentials = credentials;
    }
}
