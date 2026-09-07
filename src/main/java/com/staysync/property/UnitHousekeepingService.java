package com.staysync.property;

import com.staysync.property.domain.Unit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UnitHousekeeping} 구현.
 *
 * <p><b>{@code UnitCatalogService} 에 얹지 않는다.</b> 그 클래스는 클래스 단위로
 * {@code @Transactional(readOnly = true)} 라, 여기 쓰기 메서드를 넣으면 읽기 전용
 * 트랜잭션에 합류해 갱신이 거부된다. 예외 메시지는 "읽기 전용 트랜잭션에서는 …" 이라
 * 원인이 붙는 쪽이 아니라 쓰는 쪽으로 보인다. 13주차 {@code ConflictResolutionService}
 * 에서 이미 한 번 겪었고 CLAUDE.md 함정 목록에 있다.
 *
 * <p>전파는 기본값 {@code REQUIRED} 다. 태스크 완료와 이 갱신이 한 트랜잭션이어야
 * 하므로 부르는 쪽에 합류해야 한다.
 */
@Service
@Transactional
class UnitHousekeepingService implements UnitHousekeeping {

    private final UnitRepository unitRepo;

    UnitHousekeepingService(UnitRepository unitRepo) {
        this.unitRepo = unitRepo;
    }

    @Override
    public void markDirty(Long unitId) {
        load(unitId).markDirty();
    }

    @Override
    public void markClean(Long unitId) {
        load(unitId).markClean();
    }

    private Unit load(Long unitId) {
        return unitRepo.findById(unitId)
                .orElseThrow(() -> new UnitNotFoundException(unitId));
    }
}
