package com.staysync.property;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

/**
 * 숙소를 찾을 수 없다.
 *
 * <p>다른 조직의 숙소를 조회했을 때도 이 예외가 나간다. 403 으로 "있지만 권한이 없다"고
 * 알려 주면 식별자를 훑어 다른 조직의 숙소 존재 여부를 알아낼 수 있다. 없는 것과
 * 남의 것을 같은 응답으로 만든다.
 */
public class PropertyNotFoundException extends DomainException {

    public PropertyNotFoundException(Long propertyId) {
        super("PROPERTY_NOT_FOUND", "숙소를 찾을 수 없습니다. id=" + propertyId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
