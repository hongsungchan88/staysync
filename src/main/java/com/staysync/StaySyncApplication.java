package com.staysync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StaySync 애플리케이션 진입점.
 *
 * <p>모듈러 모놀리스로 구성한다. 최상위 패키지 하나가 모듈 하나이며,
 * 모듈 사이의 직접 호출은 금지한다. 규칙 위반은 {@code ModularityTest} 가 잡는다.
 *
 * <p>{@code shared} 는 모든 모듈이 참조할 수 있는 공유 커널이다.
 */
@SpringBootApplication
@Modulithic(sharedModules = "shared")
@EnableScheduling
public class StaySyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(StaySyncApplication.class, args);
    }
}
