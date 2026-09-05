package com.staysync.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.staysync.shared.crypto.PersonalDataCipher;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 채널 자격 증명을 암호화해 담고, 밖으로는 마스킹한 형태만 내보낸다.
 *
 * <p>복호화가 일어나는 자리를 여기 하나로 모은다. {@code GuestRegistrar} 가 게스트
 * 연락처에 대해 하는 일과 같다. 여러 곳에서 복호화하면 그중 하나가 평문을 응답이나
 * 로그에 흘리고, 그건 새는 쪽에서 아무 증상이 없다.
 *
 * <p>암호화는 {@link PersonalDataCipher} 를 그대로 쓴다(ADR 0007 의 두 번째 적용).
 * 두 번째 암호화 구현을 만들면 검증도 두 벌이 된다. 검색 해시는 만들지 않는다 —
 * 자격 증명으로 조회할 일이 없다.
 *
 * <p>저장 형태는 {@code {"api_key":"<Base64 암호문>"}} 이다. <b>키는 평문, 값만
 * 암호문이다.</b> 키 이름은 비밀이 아니고, 키까지 감추면 어떤 설정이 들어 있는지
 * 보려고 매번 복호화해야 한다.
 */
@Component
public class ChannelCredentialStore {

    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {
    };

    /** 마스킹에서 양끝에 남기는 글자 수. 이보다 짧은 값은 통째로 가린다. */
    private static final int VISIBLE = 4;

    private final PersonalDataCipher cipher;
    private final ObjectMapper objectMapper;

    ChannelCredentialStore(PersonalDataCipher cipher, ObjectMapper objectMapper) {
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    /**
     * 새 자격 증명을 암호화한다. 빈 값은 담지 않는다.
     *
     * @return JSON 문자열. 담을 것이 없으면 {@code null}
     */
    public String seal(Map<String, String> plain) {
        return merge(null, plain);
    }

    /**
     * 기존 값 위에 새 값을 얹는다.
     *
     * <p><b>빈 값은 기존 값을 유지한다.</b> 화면은 저장된 자격 증명을 다시 받지 못하므로
     * (마스킹된 형태만 본다) 표시 이름만 고치려는 수정에서 자격 증명 칸이 비어 온다.
     * 그걸 그대로 반영하면 연결이 조용히 끊긴다.
     */
    public String merge(String existingJson, Map<String, String> incoming) {
        Map<String, String> sealed = new LinkedHashMap<>(readSealed(existingJson));
        if (incoming != null) {
            incoming.forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    sealed.put(key, cipher.encrypt(value));
                }
            });
        }
        return sealed.isEmpty() ? null : write(sealed);
    }

    /**
     * 마스킹한 형태. 화면과 응답에는 이것만 나간다.
     *
     * <p>{@code chnx••••1a2b} 처럼 양끝만 남긴다. 호스트가 "내가 넣은 그 키가 맞나"를
     * 확인할 수 있으면 충분하고, 그 이상은 유출 경로만 넓힌다.
     */
    public Map<String, String> masked(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        readSealed(json).forEach((key, encrypted) -> result.put(key, mask(cipher.decrypt(encrypted))));
        return result;
    }

    /**
     * 복호화한 자격 증명. <b>어댑터에 넘길 때만 쓴다</b>(12~13주차).
     *
     * <p>응답을 만드는 경로에서는 부르지 않는다. 그러라고 {@link #masked} 가 있다.
     */
    public Map<String, String> reveal(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        readSealed(json).forEach((key, encrypted) -> result.put(key, cipher.decrypt(encrypted)));
        return result;
    }

    private static String mask(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        if (plaintext.length() < VISIBLE * 2 + 1) {
            return "••••";
        }
        return plaintext.substring(0, VISIBLE) + "••••"
                + plaintext.substring(plaintext.length() - VISIBLE);
    }

    private Map<String, String> readSealed(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("채널 자격 증명을 읽지 못했습니다.", e);
        }
    }

    private String write(Map<String, String> sealed) {
        try {
            return objectMapper.writeValueAsString(sealed);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("채널 자격 증명을 직렬화하지 못했습니다.", e);
        }
    }
}
