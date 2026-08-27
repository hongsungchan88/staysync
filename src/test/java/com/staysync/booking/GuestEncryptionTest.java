package com.staysync.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.staysync.booking.domain.Guest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 완료 조건 7. 게스트 연락처 암호화와 검색 해시. */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class GuestEncryptionTest {

    private static final String 전화번호 = "010-1234-5678";
    private static final String 이메일 = "guest@example.com";

    @Autowired
    private GuestRegistrar registrar;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("넣었다 꺼내면 원래 값이 나온다")
    void 암호화한_연락처를_복호화하면_원래_값이_나온다() {
        Guest guest = registrar.register(orgId(), "홍길동", 전화번호, 이메일);

        // 하이픈을 뺀 정규화된 형태로 돌아온다. 검색 해시가 형태에 민감하기 때문이다.
        assertThat(registrar.revealPhone(guest)).isEqualTo("01012345678");
        assertThat(registrar.revealEmail(guest)).isEqualTo(이메일);
    }

    @Test
    @DisplayName("같은 값을 두 번 암호화하면 암호문은 다르고 검색 해시는 같다")
    void 암호문은_매번_다르고_검색_해시는_같다() {
        Guest 첫번째 = registrar.register(orgId(), "홍길동", 전화번호, 이메일);
        Guest 두번째 = registrar.register(orgId(), "홍길동", 전화번호, 이메일);

        // 암호문이 같게 나오면 IV 를 재사용하고 있다는 뜻이다. GCM 에서 IV 재사용은
        // 평문 복원으로 이어지므로 이 검증이 이 테스트의 핵심이다.
        assertThat(두번째.getPhoneEnc())
                .as("IV 가 매번 새로 만들어져야 암호문이 달라진다")
                .isNotEqualTo(첫번째.getPhoneEnc());
        assertThat(두번째.getEmailEnc()).isNotEqualTo(첫번째.getEmailEnc());

        // 반대로 검색 해시는 같아야 조회가 된다. 암호화와 검색의 요구가 정반대라
        // 두 값을 따로 저장하는 것이다.
        assertThat(두번째.getPhoneHash()).isEqualTo(첫번째.getPhoneHash());
        assertThat(두번째.getEmailHash()).isEqualTo(첫번째.getEmailHash());

        // 그리고 둘 다 복호화하면 같은 값이 나온다
        assertThat(registrar.revealPhone(두번째)).isEqualTo(registrar.revealPhone(첫번째));
    }

    @Test
    void 검색_해시로_게스트를_찾을_수_있다() {
        Guest saved = registrar.register(orgId(), "김손님", "010-9999-0000", "kim@example.com");

        assertThat(saved.getPhoneHash()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM guest WHERE phone_hash = ?", Integer.class,
                saved.getPhoneHash()))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("표기가 달라도 같은 번호면 같은 해시가 나온다")
    void 하이픈이_있든_없든_같은_해시가_된다() {
        Guest 하이픈있음 = registrar.register(orgId(), "표기A", "010-1111-2222", null);
        Guest 하이픈없음 = registrar.register(orgId(), "표기B", "01011112222", null);
        Guest 공백섞임 = registrar.register(orgId(), "표기C", "010 1111 2222", null);

        assertThat(하이픈없음.getPhoneHash()).isEqualTo(하이픈있음.getPhoneHash());
        assertThat(공백섞임.getPhoneHash()).isEqualTo(하이픈있음.getPhoneHash());
    }

    @Test
    void 검색_해시는_컬럼_길이에_맞는_64자다() {
        Guest guest = registrar.register(orgId(), "길이확인", 전화번호, 이메일);

        // phone_hash 와 email_hash 는 VARCHAR(64) 다.
        assertThat(guest.getPhoneHash()).hasSize(64).matches("[0-9a-f]+");
        assertThat(guest.getEmailHash()).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("데이터베이스에 평문이 남지 않는다")
    void 저장된_행에_평문이_없다() {
        Guest guest = registrar.register(orgId(), "평문확인", 전화번호, 이메일);

        String phoneEnc = jdbc.queryForObject(
                "SELECT phone_enc FROM guest WHERE id = ?", String.class, guest.getId());
        String emailEnc = jdbc.queryForObject(
                "SELECT email_enc FROM guest WHERE id = ?", String.class, guest.getId());

        assertThat(phoneEnc).doesNotContain("01012345678").doesNotContain(전화번호);
        assertThat(emailEnc).doesNotContain(이메일).doesNotContain("guest");
    }

    @Test
    void 연락처가_없으면_암호문과_해시도_없다() {
        Guest guest = registrar.register(orgId(), "연락처없음", null, null);

        assertThat(guest.getPhoneEnc()).isNull();
        assertThat(guest.getPhoneHash()).isNull();
        assertThat(registrar.revealPhone(guest)).isNull();
    }

    private Long orgId() {
        return jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('암호화테스트') RETURNING id", Long.class);
    }
}
