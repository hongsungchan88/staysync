package com.staysync.property;

import com.staysync.property.domain.Unit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitRepository extends JpaRepository<Unit, Long> {

    List<Unit> findByPropertyIdOrderBySortOrderAscIdAsc(Long propertyId);
}
