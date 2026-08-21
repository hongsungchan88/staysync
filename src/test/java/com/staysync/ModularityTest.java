package com.staysync;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * 모듈 경계 검증.
 *
 * <p>모듈이 다른 모듈의 내부 패키지를 직접 참조하면 이 테스트가 실패한다.
 * 문서에 적어둔 규칙을 사람이 지키기를 기대하는 대신 빌드가 강제하게 만드는 장치다.
 */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(StaySyncApplication.class);

    @Test
    void 모듈_경계를_위반하지_않는다() {
        modules.verify();
    }

    @Test
    void 모듈_구조를_출력한다() {
        modules.forEach(System.out::println);
    }

    /**
     * 모듈 구조 문서를 build/spring-modulith-docs 아래에 생성한다.
     * 계획서 산출물의 아키텍처 다이어그램으로 쓸 수 있다.
     */
    @Test
    void 모듈_문서를_생성한다() {
        new Documenter(modules).writeDocumentation();
    }
}
