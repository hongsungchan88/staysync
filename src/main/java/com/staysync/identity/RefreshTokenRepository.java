package com.staysync.identity;

import com.staysync.identity.domain.RefreshToken;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 한 사용자의 살아 있는 토큰을 전부 무효화한다. 재사용이 탐지됐을 때 부른다.
     *
     * <p>체인을 거슬러 올라가며 하나씩 처리하지 않는 이유는, 유출된 쪽이 어느 갈래인지
     * 알 수 없기 때문이다. 확실한 것은 이 사용자의 토큰 중 하나가 새어 나갔다는 사실뿐이라
     * 전부 끊고 다시 로그인하게 하는 편이 안전하다.
     *
     * @return 무효화된 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now
             where t.userId = :userId
               and t.revokedAt is null
            """)
    int revokeAllOfUser(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

    /**
     * 만료된 지 오래된 토큰을 지운다.
     *
     * <p>만료 즉시 지우지 않고 유예를 두는 이유는, 만료된 토큰이 제시됐을 때 "만료"와
     * "존재한 적 없음"을 구분해 로그를 남기기 위해서다. 행이 사라지면 둘을 구분할 수 없다.
     *
     * @return 삭제된 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt < :threshold")
    int deleteExpiredBefore(@Param("threshold") OffsetDateTime threshold);
}
