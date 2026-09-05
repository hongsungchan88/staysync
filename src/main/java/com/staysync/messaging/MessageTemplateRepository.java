package com.staysync.messaging;

import com.staysync.messaging.domain.MessageTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

    List<MessageTemplate> findByOrgIdOrderByIdAsc(Long orgId);

    /** 자동 발송 규칙이 코드로 템플릿을 가리킨다. {@code uq_template_code} 가 이 경로다. */
    Optional<MessageTemplate> findByOrgIdAndCode(Long orgId, String code);
}
