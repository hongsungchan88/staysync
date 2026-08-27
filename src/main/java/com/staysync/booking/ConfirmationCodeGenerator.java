package com.staysync.booking;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 예약 확인 코드를 만든다. 형식은 {@code SS-XXXXXX} 다.
 *
 * <p>글자 집합에서 {@code 0 O 1 I L} 을 뺐다. 전화로 불러 주는 값이라 "영일"과 "오"처럼
 * 헷갈리는 글자가 섞이면 되묻는 일이 생긴다. 남는 31글자로 6자리면 약 8.9억 가지라
 * 충돌은 드물다.
 */
@Component
public class ConfirmationCodeGenerator {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 6;
    private static final String PREFIX = "SS-";

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
