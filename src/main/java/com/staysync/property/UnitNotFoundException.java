package com.staysync.property;

import com.staysync.shared.error.DomainException;
import org.springframework.http.HttpStatus;

public class UnitNotFoundException extends DomainException {

    public UnitNotFoundException(Long unitId) {
        super("UNIT_NOT_FOUND", "판매 단위를 찾을 수 없습니다. id=" + unitId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
