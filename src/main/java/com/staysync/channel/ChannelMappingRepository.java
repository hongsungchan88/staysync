package com.staysync.channel;

import com.staysync.channel.domain.ChannelMapping;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChannelMappingRepository extends JpaRepository<ChannelMapping, Long> {

    List<ChannelMapping> findByConnectionIdOrderByIdAsc(Long connectionId);

    boolean existsByConnectionIdAndUnitId(Long connectionId, Long unitId);

    /**
     * 이 판매 단위가 나가는 모든 연결. 채널 전파가 쓴다.
     *
     * <p>한 판매 단위가 채널 여럿에 매핑되는 것이 이 제품의 존재 이유이므로 목록이다.
     * {@code idx_mapping_unit} 이 이 경로를 받는다.
     */
    List<ChannelMapping> findByUnitId(Long unitId);
}
