package com.staysync.shared.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** {@code idx_audit_entity} 가 이 질의를 위한 인덱스다. */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType,
                                                                    Long entityId);
}
