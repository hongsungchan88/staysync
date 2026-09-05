package com.mockota;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Mock OTA 시뮬레이터. <b>우리 앱이 아니라 상대역이다.</b>
 *
 * <p>실제 OTA 에서 일어나지만 재현하기 어려운 악조건을 만들어 내는 것이 목적이다
 * (계획서 6.4). iCal 은 요금을 동기화하지 못하고, Channex 스테이징은 남의 서버라
 * 지연과 실패를 우리가 만들 수 없다. 그래서 지연·실패·중복·순서 역전·동시 예약은
 * 이것으로만 검증할 수 있다.
 *
 * <p><b>패키지가 {@code com.mockota} 인 것은 실수가 아니다.</b> 채널이 우리 코드를
 * 아는 것은 말이 안 되므로 {@code com.staysync} 의 어떤 타입도 참조하지 않는다.
 * 뿌리 패키지를 갈라 두면 그 규칙이 import 한 줄로 드러나고, 12주차에 이 프로젝트를
 * 백엔드 테스트 클래스패스에 올리더라도 백엔드의 컴포넌트 스캔에 딸려 들어가지 않는다.
 *
 * <p>설계 근거는 docs/adr/0011-mock-ota-시뮬레이터.md.
 */
@SpringBootApplication
@EnableConfigurationProperties(MockOtaProperties.class)
public class MockOtaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockOtaApplication.class, args);
    }
}
