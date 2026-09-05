package com.mockota;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API 키를 검사한다. 없거나 틀리면 401 이다.
 *
 * <p><b>이 검사가 없으면 12주차에 어댑터가 키를 빠뜨려도 테스트가 전부 통과한다.</b>
 * 드러나는 것은 실제 OTA 로 바꾸는 순간이고, 그때는 모든 요청이 401 이라 어디서부터
 * 잘못됐는지도 보이지 않는다. 정상 경로에서 증상이 없는 결함을 미리 막는 장치다.
 *
 * <p>서명 검증과 만료는 흉내 내지 않는다. 그건 실제 채널에서
 * {@code ChannelAdapter.verifySignature()} 가 할 일이고, 지금 흉내 내면 검증하는 대상이
 * 실제 채널이 아니라 우리 흉내가 된다.
 *
 * <p>{@code /api/**} 전체에 건다. 시나리오 엔드포인트도 포함이다 — 웹훅을 쏘게 만드는
 * 것이 그 경로라서, 여기를 열어 두면 "웹훅 발신에 키가 필요하다"가 검증되지 않는다.
 */
@Component
@Order(2)
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final byte[] expected;

    ApiKeyFilter(MockOtaProperties properties) {
        this.expected = properties.apiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null
                // 길이 차이로 갈리지 않게 상수 시간 비교를 쓴다. 흉내 내는 쪽이라도
                // 비교를 틀리게 보여 줄 이유는 없다.
                || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing " + HEADER);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
}
