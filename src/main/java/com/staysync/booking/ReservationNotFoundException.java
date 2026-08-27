package com.staysync.booking;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 예약을 찾을 수 없다.
 *
 * <p>다른 조직의 예약을 조회했을 때도 이 예외가 나간다. 403 으로 "있지만 권한이 없다"고
 * 알려 주면 식별자를 훑어 다른 조직의 예약 존재 여부를 알아낼 수 있다. 3주차의
 * {@code PropertyNotFoundException} 과 같은 방침이다.
 */
public class ReservationNotFoundException extends DomainException {

    public ReservationNotFoundException(Long reservationId) {
        super("RESERVATION_NOT_FOUND", "예약을 찾을 수 없습니다. id=" + reservationId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
