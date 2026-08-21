package com.staysync.property;

import com.staysync.property.domain.RatePlan;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RatePlanRepository extends JpaRepository<RatePlan, Long> {

    List<RatePlan> findByUnitId(Long unitId);

    Optional<RatePlan> findByUnitIdAndIsDefaultTrue(Long unitId);
}
