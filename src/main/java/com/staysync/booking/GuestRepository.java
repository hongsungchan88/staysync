package com.staysync.booking;

import com.staysync.booking.domain.Guest;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestRepository extends JpaRepository<Guest, Long> {

    /**
     * 검색용 해시로 찾는다. 평문 연락처로는 조회할 수 없다.
     *
     * <p>해시가 HMAC 이라 같은 입력이 같은 출력이 된다. 그래서 이 조회가 가능하다.
     * 솔트를 붙였다면 값마다 해시가 달라 조회 자체가 성립하지 않는다(ADR 0007).
     */
    List<Guest> findByPhoneHash(String phoneHash);

    List<Guest> findByEmailHash(String emailHash);
}
