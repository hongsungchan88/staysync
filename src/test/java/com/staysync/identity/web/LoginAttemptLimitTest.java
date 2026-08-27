package com.staysync.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.support.ApiTestBase;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 완료 조건 5. 로그인 시도 제한. */
class LoginAttemptLimitTest extends ApiTestBase {

    private static final String 올바른비밀번호 = "충분히긴비밀번호1234";
    private static final String 틀린비밀번호 = "틀린비밀번호9999";

    @Test
    @DisplayName("로그인을 5회 실패하면 429 가 나온다")
    void 로그인_다섯번_실패하면_429가_나온다() throws Exception {
        String email = 새이메일();
        가입(email);

        // 5회까지는 실패한 요청 자체이므로 401 이다. 잠금은 5회를 채운 뒤부터 걸린다.
        for (int i = 1; i <= 5; i++) {
            assertThat(로그인시도(email, 틀린비밀번호).getResponse().getStatus())
                    .as("%d회째 실패 요청 자체는 401 이다", i)
                    .isEqualTo(401);
        }
        // 5회를 채웠으므로 이후 요청이 거절된다
        assertThat(로그인시도(email, 틀린비밀번호).getResponse().getStatus()).isEqualTo(429);

        // 잠긴 뒤에는 올바른 비밀번호도 거절된다
        assertThat(로그인시도(email, 올바른비밀번호).getResponse().getStatus())
                .as("잠금은 비밀번호가 맞아도 유지된다")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("존재하지 않는 이메일도 같은 응답을 준다")
    void 존재하지_않는_이메일도_같은_응답을_준다() throws Exception {
        String 없는이메일 = 새이메일();

        // 가입한 적 없는 이메일인데도 실패 응답과 잠금 동작이 같아야 한다.
        // 다르면 429 가 나오는지 여부만으로 가입 여부를 알 수 있다.
        for (int i = 1; i <= 5; i++) {
            assertThat(로그인시도(없는이메일, 틀린비밀번호).getResponse().getStatus())
                    .isEqualTo(401);
        }
        assertThat(로그인시도(없는이메일, 틀린비밀번호).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void 실패_응답은_사유를_구분하지_않는다() throws Exception {
        String 가입한이메일 = 새이메일();
        가입(가입한이메일);
        String 없는이메일 = 새이메일();

        String 틀린비밀번호응답 = 로그인시도(가입한이메일, 틀린비밀번호).getResponse().getContentAsString();
        String 없는계정응답 = 로그인시도(없는이메일, 틀린비밀번호).getResponse().getContentAsString();

        // occurredAt 이 달라 본문 전체는 같지 않다. 코드와 메시지가 같은지를 본다.
        assertThat(json.readTree(틀린비밀번호응답).get("code").asText())
                .isEqualTo(json.readTree(없는계정응답).get("code").asText())
                .isEqualTo("LOGIN_FAILED");
        assertThat(json.readTree(틀린비밀번호응답).get("message").asText())
                .isEqualTo(json.readTree(없는계정응답).get("message").asText());
    }

    @Test
    void 로그인에_성공하면_카운터가_지워진다() throws Exception {
        String email = 새이메일();
        가입(email);

        로그인시도(email, 틀린비밀번호);
        로그인시도(email, 틀린비밀번호);
        assertThat(로그인시도(email, 올바른비밀번호).getResponse().getStatus()).isEqualTo(200);

        // 카운터가 지워졌으므로 다시 5회까지는 401 이어야 한다
        for (int i = 1; i <= 5; i++) {
            assertThat(로그인시도(email, 틀린비밀번호).getResponse().getStatus())
                    .as("성공 이후 %d회째", i)
                    .isEqualTo(401);
        }
    }

    /** 시도 제한이 이메일 단위라 테스트마다 새 이메일이어야 서로 간섭하지 않는다. */
    private static String 새이메일() {
        return "limit-" + UUID.randomUUID() + "@example.com";
    }
}
