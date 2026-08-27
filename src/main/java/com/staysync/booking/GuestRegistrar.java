package com.staysync.booking;

import com.staysync.booking.domain.Guest;
import com.staysync.shared.crypto.PersonalDataCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게스트를 만든다. 평문이 이 클래스 밖으로 나가지 않게 하는 것이 목적이다.
 *
 * <p>{@code Guest} 생성자는 이미 암호화된 값만 받는다. 평문을 암호문으로 바꾸는 자리를
 * 여기 하나로 좁혀 두면, 어딘가에서 평문을 그대로 넣는 경로가 생기기 어렵다.
 *
 * <p>정규화를 먼저 한다. 검색 해시는 입력이 한 글자만 달라도 완전히 달라지므로,
 * {@code 010-1234-5678} 과 {@code 01012345678} 이 다른 해시가 되면 같은 사람을 찾지
 * 못한다. 저장 전에 형태를 맞춘다.
 */
@Service
public class GuestRegistrar {

    private final GuestRepository guestRepo;
    private final PersonalDataCipher cipher;

    GuestRegistrar(GuestRepository guestRepo, PersonalDataCipher cipher) {
        this.guestRepo = guestRepo;
        this.cipher = cipher;
    }

    @Transactional
    public Guest register(Long orgId, String name, String phone, String email) {
        String normalizedPhone = normalizePhone(phone);
        String normalizedEmail = normalizeEmail(email);

        return guestRepo.save(new Guest(
                orgId,
                name,
                cipher.encrypt(normalizedPhone),
                cipher.searchHash(normalizedPhone),
                cipher.encrypt(normalizedEmail),
                cipher.searchHash(normalizedEmail)));
    }

    /** 저장된 연락처를 평문으로 되돌린다. 화면 마스킹과 조회 기록은 P5 에서 본다. */
    @Transactional(readOnly = true)
    public String revealPhone(Guest guest) {
        return cipher.decrypt(guest.getPhoneEnc());
    }

    @Transactional(readOnly = true)
    public String revealEmail(Guest guest) {
        return cipher.decrypt(guest.getEmailEnc());
    }

    /** 숫자만 남긴다. 하이픈과 공백이 섞여도 같은 해시가 나와야 검색이 된다. */
    static String normalizePhone(String phone) {
        return phone == null ? null : phone.replaceAll("[^0-9]", "");
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
