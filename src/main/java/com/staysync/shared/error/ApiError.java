package com.staysync.shared.error;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * API 오류 응답 본문.
 *
 * @param code    기계가 읽는 오류 코드 (예: INVENTORY_SOLD_OUT)
 * @param message 사람이 읽는 설명
 * @param details 필드 단위 오류 목록. 없으면 빈 리스트
 */
public record ApiError(
        String code,
        String message,
        List<String> details,
        OffsetDateTime occurredAt) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, List.of(), OffsetDateTime.now());
    }

    public static ApiError of(String code, String message, List<String> details) {
        return new ApiError(code, message, details, OffsetDateTime.now());
    }
}
