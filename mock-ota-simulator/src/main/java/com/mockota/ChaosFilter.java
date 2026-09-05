package com.mockota;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 지연과 실패를 주입한다. 계획서 6.4 의 {@code ChaosFilter} 다.
 *
 * <p><b>채널 표면에만 건다.</b> {@code /api/ari} 와 {@code /api/bookings} 가 대상이고
 * {@code /api/scenarios/**} 는 지나간다. 악조건이 겨냥하는 것은 <i>우리 클라이언트</i>의
 * 타임아웃·재시도·백오프이고, 시나리오 엔드포인트는 채널의 표면이 아니라 테스트가 쥐는
 * 손잡이다. 손잡이까지 실패시키면 에러율 100% 에서 시험 준비 자체가 불가능해진다.
 */
@Component
@Order(1)
public class ChaosFilter extends OncePerRequestFilter {

    private final long latencyMs;
    private final ChaosDice dice;

    ChaosFilter(MockOtaProperties properties, ChaosDice dice) {
        this.latencyMs = properties.chaos().latencyMs();
        this.dice = dice;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("지연 주입이 중단됐습니다.", e);
            }
        }
        if (dice.shouldFail()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Chaos: injected failure");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/ari")
                && !request.getRequestURI().startsWith("/api/bookings");
    }
}
