package com.staysync.channel.port;

import com.staysync.shared.error.DomainException;
import java.time.Duration;
import org.springframework.http.HttpStatus;

/**
 * 채널과 이야기하다 생긴 실패. <b>세 갈래로 나뉜다</b>(계획서 13.3).
 *
 * <p>이 구분이 재시도 정책 전체를 좌우한다. 하나로 뭉뚱그리면 둘 중 하나가 된다 —
 * 전부 재시도해서 매핑 오류를 8번씩 두드리거나, 전부 포기해서 잠깐의 네트워크 문제에
 * 요금 갱신을 잃는다. 어느 쪽이든 조용히 일어난다.
 *
 * <p>어댑터가 채널의 응답을 이 셋 중 하나로 옮기고, {@code SyncJobWorker} 는 타입만
 * 보고 처리한다. 채널마다 다른 상태 코드 해석이 어댑터 안에 갇히는 것이 요점이다.
 *
 * <p>근거는 docs/adr/0012-채널-재시도-정책.md.
 */
public abstract class ChannelException extends DomainException {

    protected ChannelException(String code, String message) {
        super(code, message);
    }

    /**
     * 요청 한도에 걸렸다. 채널이 언제 다시 오라고 알려 준다.
     *
     * <p>실패가 아니라 <b>속도 조절</b>이다. 백오프를 올리지 않는다 — 한도는 시간이
     * 지나면 그냥 풀리고, 여기서 지수적으로 물러나면 밀린 작업이 계속 밀린다.
     */
    public static class RateLimitedException extends ChannelException {

        private final Duration retryAfter;

        public RateLimitedException(Duration retryAfter) {
            super("CHANNEL_RATE_LIMITED", "채널 요청 한도에 걸렸습니다. " + retryAfter + " 뒤에 다시 시도합니다.");
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
    }

    /**
     * 지금은 안 되지만 나중에는 될 실패. 5xx, 타임아웃, 연결 실패다.
     *
     * <p>지수 백오프로 재시도한다. 시뮬레이터의 에러율 주입이 만드는 것이 이것이고,
     * 12주차 완료 조건 2의 "재시도 후 최종 정합성"이 이 갈래 위에 선다.
     */
    public static class TransientChannelException extends ChannelException {

        public TransientChannelException(String message) {
            super("CHANNEL_TRANSIENT", message);
        }

        public TransientChannelException(String message, Throwable cause) {
            super("CHANNEL_TRANSIENT", message);
            initCause(cause);
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.BAD_GATEWAY;
        }
    }

    /**
     * 몇 번을 다시 보내도 같은 답이 올 실패. 잘못된 매핑, 없는 객실, 틀린 자격 증명이다.
     *
     * <p><b>즉시 {@code DEAD} 로 보낸다.</b> 재시도가 고칠 수 있는 것이 아니라 사람이
     * 매핑이나 자격 증명을 고쳐야 한다. 8번 두드려 봐야 로그만 늘고 그 사이 뒤의
     * 작업이 막힌다.
     */
    public static class PermanentChannelException extends ChannelException {

        public PermanentChannelException(String message) {
            super("CHANNEL_PERMANENT", message);
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.BAD_GATEWAY;
        }
    }
}
