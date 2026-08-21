package com.staysync.booking;

/** 재고 원장에 적용할 연산. */
public enum LedgerAction {
    BOOK,
    HOLD,
    PROMOTE,
    RELEASE_HOLD,
    RELEASE,
    FORCE_BOOK
}
