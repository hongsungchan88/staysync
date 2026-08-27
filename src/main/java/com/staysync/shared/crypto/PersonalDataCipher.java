package com.staysync.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 개인정보를 AES-256-GCM 으로 암호화하고, 검색용 HMAC 해시를 만든다.
 *
 * <p>계획서 15.2 가 약속한 "연락처와 이메일은 컬럼 암호화하고 검색용 해시 인덱스를 둔다"를
 * 구현한다. 설계 근거는 docs/adr/0007-게스트-개인정보-암호화.md.
 *
 * <p>암호화와 검색은 요구가 정반대다. 암호화는 같은 입력이 매번 다른 출력이어야 하고,
 * 검색은 같은 입력이 매번 같은 출력이어야 한다. 그래서 두 값을 따로 저장한다.
 */
@Component
@EnableConfigurationProperties(CryptoProperties.class)
public class PersonalDataCipher {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String MAC = "HmacSHA256";

    /** GCM 권장 IV 길이. 12바이트여야 내부 변환 없이 그대로 쓰인다. */
    private static final int IV_BYTES = 12;

    /** GCM 인증 태그 길이(비트). */
    private static final int TAG_BITS = 128;

    /**
     * 검색 해시 키를 파생시킬 때 쓰는 라벨.
     *
     * <p>암호화 키와 해시 키를 같은 바이트열로 쓰지 않기 위한 것이다. 한 용도의 키가
     * 다른 용도에 그대로 쓰이면 한쪽의 약점이 다른 쪽으로 번진다.
     */
    private static final byte[] SEARCH_KEY_LABEL = "staysync:search-hash:v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec searchKey;
    private final SecureRandom random = new SecureRandom();

    public PersonalDataCipher(CryptoProperties properties) {
        byte[] master = properties.keyBytes();
        this.encryptionKey = new SecretKeySpec(master, "AES");
        this.searchKey = new SecretKeySpec(deriveSearchKey(master), MAC);
    }

    /**
     * 암호화한다.
     *
     * <p>IV 를 매번 새로 만들어 암호문 앞에 붙인다. GCM 은 같은 키로 같은 IV 를 두 번 쓰면
     * 평문을 복원할 수 있게 되므로 재사용이 곧 사고다. 그래서 IV 를 상수로 두거나 어딘가에
     * 보관해 돌려쓰지 않고, 암호문과 한 덩어리로 만들어 함께 저장한다.
     *
     * @return {@code Base64(IV || 암호문+태그)}. 컬럼은 TEXT 다
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("개인정보 암호화에 실패했습니다.", e);
        }
    }

    /**
     * 복호화한다.
     *
     * <p>GCM 은 인증 암호라 암호문이 손상되면 복호화 자체가 실패한다. 조용히 깨진 값을
     * 돌려주지 않는다.
     */
    public String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] decrypted = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("개인정보 복호화에 실패했습니다. 키가 바뀌었거나 값이 손상됐습니다.", e);
        }
    }

    /**
     * 검색용 해시.
     *
     * <p><b>맨 SHA-256 을 쓰지 않는다.</b> 국내 휴대폰 번호는 사실상 1억 가지뿐이라
     * 전부 미리 해시해 두면 대조표 한 번으로 되돌릴 수 있다. 비밀번호가 아니라서 솔트를
     * 붙일 수도 없다. 솔트가 값마다 다르면 해시로 조회하는 것 자체가 불가능해진다.
     *
     * <p>그래서 키를 섞는 HMAC-SHA256 을 쓴다. 키가 고정이라 같은 입력은 같은 출력이 되어
     * 조회가 되고, 키를 모르면 대조표를 만들 수 없다.
     *
     * @return 소문자 16진수 64자. {@code VARCHAR(64)} 에 맞는다
     */
    public String searchHash(String value) {
        if (value == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(MAC);
            mac.init(searchKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("검색 해시 생성에 실패했습니다.", e);
        }
    }

    private static byte[] deriveSearchKey(byte[] master) {
        try {
            Mac mac = Mac.getInstance(MAC);
            mac.init(new SecretKeySpec(master, MAC));
            return mac.doFinal(SEARCH_KEY_LABEL);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("검색 해시 키 파생에 실패했습니다.", e);
        }
    }
}
