package com.mockota;

import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 이번 요청을 실패시킬지 정한다.
 *
 * <p><b>주입이 재현되지 않으면 12주차에 쓸 수 없다.</b> "가끔 실패하는 테스트"는
 * 결함을 숨긴다 — 실패가 주입 때문인지 코드 결함 때문인지 구분되지 않으면 그 테스트는
 * 아무것도 보증하지 않는다. 그래서 시드를 고정하는 경로를 남긴다.
 *
 * <p>{@link java.util.concurrent.ThreadLocalRandom} 을 쓰지 않는 이유가 여기 있다.
 * 계획서 6.4 의 스케치는 그걸 썼지만 시드를 줄 수 없어 재현이 안 된다. 대신 시드를
 * 받는 {@link Random} 하나를 두고 뽑는 자리를 직렬화한다. 순차 요청이면 시드가 같을 때
 * 실패 순서가 같다.
 *
 * <p>동시 요청에서는 어느 스레드가 몇 번째 눈을 가져가는지가 정해지지 않으므로 순서까지
 * 같지는 않다. 12주차의 "5% 에러율로 ARI 100건"은 순차 전송이라 그대로 재현된다.
 */
@Component
public class ChaosDice {

    private static final Logger log = LoggerFactory.getLogger(ChaosDice.class);

    private final double errorRate;
    private final Random random;

    // 생성자가 둘이라 어느 쪽으로 주입할지 알려 줘야 한다. 아래 것은 테스트가
    // 시드를 직접 넣어 재현성을 확인하는 통로다.
    @Autowired
    ChaosDice(MockOtaProperties properties) {
        this(properties.chaos().errorRate(), properties.chaos().seed());
    }

    /** 테스트가 직접 만들어 재현성을 확인한다. */
    public ChaosDice(double errorRate, Long seed) {
        this.errorRate = errorRate;
        long effectiveSeed = seed != null ? seed : new Random().nextLong();
        this.random = new Random(effectiveSeed);
        if (errorRate > 0.0) {
            // 시드를 지정하지 않았어도 로그에는 남긴다. 실패를 나중에 다시 만들려면
            // 그때 쓰인 시드를 알아야 한다.
            log.info("악조건 주입: errorRate={} seed={}{}",
                    errorRate, effectiveSeed, seed == null ? " (자동 생성)" : "");
        }
    }

    /**
     * 실패시킬 차례인가.
     *
     * <p>{@code synchronized} 는 성능이 아니라 재현성을 위한 것이다. 눈을 뽑는 순서가
     * 정해져야 같은 시드가 같은 결과를 낸다.
     */
    public synchronized boolean shouldFail() {
        // 비율이 0 이어도 눈을 뽑는다. 뽑지 않으면 errorRate 를 바꿨을 때 같은 시드가
        // 다른 순서를 내고, 그러면 "시드가 같으면 같다"가 조건부 참이 된다.
        return random.nextDouble() < errorRate;
    }
}
