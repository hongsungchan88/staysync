package com.staysync.booking;

import com.staysync.shared.error.DomainException;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;

/**
 * 그 날짜는 팔 수 없다. 수량이 없거나 판매중지다.
 *
 * <p>둘을 구분해 알려 주지 않는다. 게스트에게는 "그 날은 안 된다"가 전부이고,
 * 재고가 몇 개 남았는지는 우리 운영 사정이다.
 */
public class UnavailableDateException extends DomainException {

    public UnavailableDateException(LocalDate date) {
        super("DATE_UNAVAILABLE", "선택한 날짜는 예약할 수 없습니다. date=" + date);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
