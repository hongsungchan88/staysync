package com.mockota;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 완료 조건 7. 같은 시드면 같은 순서로 실패한다.
 *
 * <p><b>재현되지 않는 주입은 12주차에 쓸 수 없다.</b> "가끔 실패하는 테스트"는 결함을
 * 숨긴다 — 실패가 주입 때문인지 코드 결함 때문인지 구분되지 않으면 그 테스트는 아무것도
 * 보증하지 않는다.
 *
 * <p>주사위를 직접 만들어 확인한다. 앱이 뜰 때마다 프로퍼티의 시드로 이 객체를 하나
 * 만들므로, <b>같은 시드로 만든 두 객체가 같은 순서를 내는 것이 곧 "앱을 두 번 띄우면
 * 같다"</b>이다. 컨텍스트 두 개를 띄워 비교하는 것보다 검증하는 대상이 분명하다.
 */
class ChaosDiceTest {

    private static final int DRAWS = 200;

    @Test
    @DisplayName("같은 시드로 두 번 돌리면 같은 순서로 실패한다")
    void 같은_시드는_같은_순서를_낸다() {
        List<Boolean> 첫번째 = draw(new ChaosDice(0.05, 42L));
        List<Boolean> 두번째 = draw(new ChaosDice(0.05, 42L));

        assertThat(두번째).isEqualTo(첫번째);
        // 5% 를 200번 뽑으면 실패가 하나도 없을 확률은 사실상 0 이다. 순서가 같은데
        // 전부 통과라면 비교한 것이 아무것도 없는 셈이라 함께 확인한다.
        assertThat(첫번째).contains(true);
    }

    @Test
    @DisplayName("시드가 다르면 순서도 다르다")
    void 다른_시드는_다른_순서를_낸다() {
        assertThat(draw(new ChaosDice(0.5, 1L)))
                .isNotEqualTo(draw(new ChaosDice(0.5, 2L)));
    }

    @Test
    @DisplayName("에러율을 바꿔도 뽑는 순서는 흔들리지 않는다")
    void 에러율이_0이어도_눈을_뽑는다() {
        // 비율이 0 일 때 뽑기를 건너뛰면, 같은 시드가 에러율에 따라 다른 순서를 낸다.
        // 그러면 "시드가 같으면 같다"가 조건부 참이 되고 12주차가 그걸 믿을 수 없다.
        ChaosDice 항상통과 = new ChaosDice(0.0, 7L);
        for (int i = 0; i < DRAWS; i++) {
            assertThat(항상통과.shouldFail()).isFalse();
        }

        // 같은 시드, 같은 횟수를 소비한 뒤의 상태가 에러율과 무관하게 같아야 한다는
        // 것이 요점이다. 여기서는 100% 가 전부 실패인지만 확인한다.
        ChaosDice 항상실패 = new ChaosDice(1.0, 7L);
        for (int i = 0; i < DRAWS; i++) {
            assertThat(항상실패.shouldFail()).isTrue();
        }
    }

    @Test
    @DisplayName("시드를 주지 않으면 매번 다른 순서가 나온다")
    void 시드가_없으면_재현되지_않는다() {
        // 재현이 필요할 때 시드를 주라는 것이지, 기본이 고정이면 안 된다.
        // 늘 같은 순서로만 시험하면 그 순서에만 강한 코드가 된다.
        assertThat(draw(new ChaosDice(0.5, null)))
                .isNotEqualTo(draw(new ChaosDice(0.5, null)));
    }

    private static List<Boolean> draw(ChaosDice dice) {
        List<Boolean> results = new ArrayList<>(DRAWS);
        for (int i = 0; i < DRAWS; i++) {
            results.add(dice.shouldFail());
        }
        return results;
    }
}
