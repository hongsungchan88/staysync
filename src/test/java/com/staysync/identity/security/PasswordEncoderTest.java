package com.staysync.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 인코더 설정 검증.
 *
 * <p>BCrypt 알고리즘 자체가 올바른지는 확인하지 않는다. 그건 라이브러리의 책임이고,
 * 우리가 틀릴 수 있는 것은 "계획서 15.1 이 정한 cost 12 가 실제로 적용됐는가"뿐이다.
 * 그래서 직접 만든 인코더가 아니라 {@link SecurityConfig} 가 등록하는 빈을 검사한다.
 * 설정에서 강도를 지정하지 않으면 기본값 10 이 조용히 쓰이는데, 해시를 보기 전에는
 * 알아챌 방법이 없다.
 */
class PasswordEncoderTest {

    /** BCrypt 해시의 접두어. $2a 는 버전, 12 는 cost. */
    private static final String COST_12_PREFIX = "$2a$12$";

    private final PasswordEncoder encoder = new SecurityConfig().passwordEncoder();

    @Test
    void 해시에_cost_12가_적용된다() {
        String 해시 = encoder.encode("비밀번호1234");

        assertThat(해시).startsWith(COST_12_PREFIX);
    }

    @Test
    void 같은_비밀번호도_해시가_매번_다르다() {
        String 원문 = "비밀번호1234";

        assertThat(encoder.encode(원문)).isNotEqualTo(encoder.encode(원문));
    }

    @Test
    void 해시는_원문과_대조하면_일치한다() {
        String 원문 = "비밀번호1234";
        String 해시 = encoder.encode(원문);

        assertThat(encoder.matches(원문, 해시)).isTrue();
        assertThat(encoder.matches("다른비밀번호", 해시)).isFalse();
    }

    @Test
    void 해시_길이가_스키마_컬럼에_들어간다() {
        // user_account.password_hash 는 VARCHAR(100) 이다. BCrypt 해시는 60자라 넉넉하다.
        assertThat(encoder.encode("비밀번호1234")).hasSizeLessThanOrEqualTo(100);
    }
}
