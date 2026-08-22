package com.staysync.identity.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * 조직. 모든 데이터 접근의 스코프 단위다.
 *
 * <p>대상 사용자가 개인 호스트라 대부분 조직 하나에 사용자 하나지만, 숙소가 늘어
 * 매니저나 청소 담당자를 두는 경우를 위해 계층을 남겨 둔다.
 */
@Entity
@Table(name = "organization")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Organization() {
    }

    public Organization(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void rename(String name) {
        this.name = name;
    }
}
