package com.staysync.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.staysync.support.ApiTestBase;
import java.nio.charset.Charset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 읽을 수 없는 요청 본문이 400 으로 나가는지 확인한다.
 *
 * <p>핸들러가 없을 때는 {@code Exception} 폴백으로 떨어져 500 이 나갔다. 정상 경로에서는
 * 드러나지 않고, 프론트엔드나 curl 이 실제로 잘못된 바이트를 보내야 보인다.
 */
class MalformedRequestTest extends ApiTestBase {

    @Test
    @DisplayName("깨진 JSON 은 500 이 아니라 400 이다")
    void 깨진_JSON은_400이다() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"a@b.c\", \"password\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("UTF-8 이 아닌 바이트가 섞여도 400 이다")
    void 잘못된_인코딩도_400이다() throws Exception {
        // 한글을 MS949 로 인코딩해 보낸다. 서버는 UTF-8 로 읽으므로 중간 바이트가
        // 깨진다. 실제로 이 경로에서 500 이 나가는 것을 발견했다.
        byte[] ms949 = "{\"email\":\"a@b.c\",\"password\":\"비밀번호1234\"}"
                .getBytes(Charset.forName("MS949"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ms949))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void 본문이_아예_없어도_400이다() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("본문은 읽혔지만 값이 비면 검증 오류로 갈린다")
    void 값_검증_실패는_다른_코드로_나간다() throws Exception {
        // 400 이라는 점은 같지만 코드가 달라야 한다. 읽지 못한 것과 읽었는데 값이
        // 틀린 것은 클라이언트가 고칠 지점이 다르다.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"","password":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
