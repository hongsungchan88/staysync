package com.staysync.messaging;

import com.staysync.messaging.domain.MessageRule;
import com.staysync.messaging.domain.MessageTrigger;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MessageRuleRepository extends JpaRepository<MessageRule, Long> {

    List<MessageRule> findByOrgIdOrderByIdAsc(Long orgId);

    /**
     * 켜져 있는 규칙만. {@code idx_rule_enabled} 가 이 경로다.
     *
     * <p>거르는 자리를 쿼리에 둔다. 부르는 쪽에서 걸러야 하면 한 곳만 빠뜨려도
     * 꺼 둔 규칙이 나가고, 그건 되돌릴 수 없다.
     */
    @Query("""
            select r from MessageRule r
            where r.orgId = :orgId and r.triggerType = :trigger and r.enabled = true
            order by r.id asc
            """)
    List<MessageRule> findEnabled(@Param("orgId") Long orgId,
                                  @Param("trigger") MessageTrigger trigger);

    /** 켜져 있는 규칙을 가진 조직. 시간 기반 스케줄러가 훑을 범위를 좁힌다. */
    @Query("""
            select distinct r.orgId from MessageRule r
            where r.triggerType in :triggers and r.enabled = true
            """)
    List<Long> orgIdsWithEnabled(@Param("triggers") List<MessageTrigger> triggers);
}
