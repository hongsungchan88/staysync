package com.staysync.channel.port;

import java.time.Duration;

/**
 * 채널 전송 결과.
 *
 * @param success       성공 여부
 * @param appliedCount  실제로 반영된 항목 수
 * @param message       실패 사유 또는 부가 설명
 * @param retryAfter    재시도까지 기다려야 할 시간. 요청 한도에 걸린 경우 채널이 알려준다
 */
public record SyncResult(boolean success, int appliedCount, String message, Duration retryAfter) {

    public static SyncResult ok(int appliedCount) {
        return new SyncResult(true, appliedCount, null, null);
    }

    public static SyncResult rateLimited(Duration retryAfter) {
        return new SyncResult(false, 0, "채널 요청 한도에 걸렸습니다.", retryAfter);
    }

    public static SyncResult failed(String message) {
        return new SyncResult(false, 0, message, null);
    }
}
