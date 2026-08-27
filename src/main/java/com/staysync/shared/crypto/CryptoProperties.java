package com.staysync.shared.crypto;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 개인정보 암호화 설정. {@code staysync.security.crypto.*} 에 대응한다.
 *
 * <p>키 하나만 받고 용도별 키는 여기서 파생시킨다. 암호화 키와 검색 해시 키를 각각
 * 환경 변수로 두면 설정이 늘고 둘 중 하나만 바뀌는 사고가 생긴다. 근거는
 * docs/adr/0007-게스트-개인정보-암호화.md.
 *
 * @param masterKey Base64 로 인코딩한 32바이트 키
 */
@ConfigurationProperties(prefix = "staysync.security.crypto")
public record CryptoProperties(String masterKey) {

    /** AES-256 이 요구하는 키 길이. */
    static final int KEY_BYTES = 32;

    public CryptoProperties {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException(
                    "게스트 개인정보 암호화 키가 설정되지 않았습니다. 환경 변수 GUEST_DATA_KEY 를 "
                            + "지정하세요. (.env.example 참고)");
        }
        if (decodeOrFail(masterKey).length != KEY_BYTES) {
            throw new IllegalStateException(
                    "게스트 개인정보 암호화 키는 Base64 로 인코딩한 " + KEY_BYTES + "바이트여야 합니다. "
                            + "생성 예: openssl rand -base64 32");
        }
    }

    byte[] keyBytes() {
        return decodeOrFail(masterKey);
    }

    private static byte[] decodeOrFail(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("암호화 키가 올바른 Base64 가 아닙니다.", e);
        }
    }
}
