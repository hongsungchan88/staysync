package com.staysync.booking.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 게스트.
 *
 * <p>이름은 평문이지만 연락처와 이메일은 암호문과 검색용 해시를 따로 저장한다.
 * 계획서 15.2 와 ADR 0007 에 따른다.
 *
 * <p>이 엔티티는 암호화 방법을 모른다. 이미 암호화된 값을 받아 담기만 한다. 평문을
 * 넘기면 그대로 저장되므로, 만드는 경로를 {@code GuestRegistrar} 하나로 좁혀 둔다.
 */
@Entity
@Table(name = "guest")
public class Guest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "phone_enc")
    private String phoneEnc;

    @Column(name = "phone_hash", length = 64)
    private String phoneHash;

    @Column(name = "email_enc")
    private String emailEnc;

    @Column(name = "email_hash", length = 64)
    private String emailHash;

    @Column(nullable = false, length = 10)
    private String locale = "ko";

    @Column
    private String memo;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Guest() {
    }

    /** 암호화가 끝난 값으로 만든다. 평문을 받지 않는 것이 이 생성자의 요점이다. */
    public Guest(Long orgId, String name,
                 String phoneEnc, String phoneHash,
                 String emailEnc, String emailHash) {
        this.orgId = orgId;
        this.name = name;
        this.phoneEnc = phoneEnc;
        this.phoneHash = phoneHash;
        this.emailEnc = emailEnc;
        this.emailHash = emailHash;
    }

    public Long getId() {
        return id;
    }

    public Long getOrgId() {
        return orgId;
    }

    public String getName() {
        return name;
    }

    /** 암호문 그대로. 평문이 필요하면 복호화를 거쳐야 한다. */
    public String getPhoneEnc() {
        return phoneEnc;
    }

    public String getEmailEnc() {
        return emailEnc;
    }

    public String getPhoneHash() {
        return phoneHash;
    }

    public String getEmailHash() {
        return emailHash;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void changeMemo(String newMemo) {
        this.memo = newMemo;
    }
}
