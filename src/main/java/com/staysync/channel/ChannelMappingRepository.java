package com.staysync.channel;

import com.staysync.channel.domain.ChannelMapping;
import java.util.List;
import java.util.Optional;
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

    /**
     * 발행 URL 의 토큰으로 매핑을 찾는다. {@code uq_mapping_export_token} 이 경로다.
     *
     * <p>없으면 비어 온다. 부르는 쪽은 404 로 답하고 <b>존재 여부를 알리지 않는다</b> —
     * 토큰이 URL 자체의 인증이라 "그런 토큰은 없다"와 "권한이 없다"를 구분해 주면
     * 대입으로 유효한 토큰을 찾을 수 있게 된다.
     */
    Optional<ChannelMapping> findByExportToken(String exportToken);
}
