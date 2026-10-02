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
     * 한 로그인의 토큰 묶음을 전부 무효화한다. 유예 밖의 재사용과 로그아웃에서 부른다.
     *
     * <p>묶음 안에서는 갈래를 가리지 않는다 — 정상 사용자와 공격자가 같은 토큰에서 갈라졌을 때 어느 쪽이
     * 진짜인지 알 수 없다. 대신 <b>묶음 밖(같은 계정의 다른 로그인)은 건드리지 않는다.</b> 토큰 하나가 샜다는
     * 것이 다른 기기의 토큰이 샜다는 뜻은 아니고, 공용 계정에서는 다른 사람이다(ADR 0005 결과 절).
     *
     * @return 무효화된 행 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now
             where t.familyId = :familyId
               and t.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") String familyId, @Param("now") OffsetDateTime now);

    /**
     * 조건부 회전. <b>아직 교체되지 않았고 무효화되지 않은 경우에만</b> 교체 표시를 한다.
     *
     * <p>읽고 나서 쓰는 방식이면 같은 순간의 두 요청이 둘 다 "아직 교체 안 됨"을 보고 회전이 두 갈래가 돼
     * 재사용 탐지를 피한다(확인-12 5.1 ③). 행 잠금이 둘째를 첫째의 커밋 뒤로 미루고, 조건을 다시 본 둘째는 0 행이다.
     *
     * @return 1 이면 이 요청이 회전했다. 0 이면 그 사이 다른 요청이 회전했거나 무효화됐다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.replacedBy = :successorId,
                   t.rotatedAt = :now
             where t.id = :id
               and t.replacedBy is null
               and t.revokedAt is null
            """)
    int markRotated(@Param("id") Long id, @Param("successorId") Long successorId,
                    @Param("now") OffsetDateTime now);

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
