package com.staysync.property;

import com.staysync.property.domain.Property;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyRepository extends JpaRepository<Property, Long> {

    List<Property> findByOrgId(Long orgId);
}
