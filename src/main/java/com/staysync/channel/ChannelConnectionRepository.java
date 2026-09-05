package com.staysync.channel;

import com.staysync.channel.domain.ChannelConnection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChannelConnectionRepository extends JpaRepository<ChannelConnection, Long> {

    /** 조직의 숙소 전부를 훑는다. 개인 호스트는 숙소가 한두 개다. */
    List<ChannelConnection> findByPropertyIdInOrderByIdAsc(List<Long> propertyIds);

    boolean existsByPropertyIdAndChannelCode(Long propertyId, String channelCode);
}
