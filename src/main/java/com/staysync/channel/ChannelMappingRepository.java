package com.staysync.channel;

import com.staysync.channel.domain.ChannelMapping;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChannelMappingRepository extends JpaRepository<ChannelMapping, Long> {

    List<ChannelMapping> findByConnectionIdOrderByIdAsc(Long connectionId);

    boolean existsByConnectionIdAndUnitId(Long connectionId, Long unitId);
}
