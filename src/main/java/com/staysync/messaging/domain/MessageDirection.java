package com.staysync.messaging.domain;

/** 메시지의 방향. {@code chk_msg_direction} 과 짝이다. */
public enum MessageDirection {

    /** 게스트 → 우리. */
    INBOUND,

    /** 우리 → 게스트. <b>보낸 뒤에는 되돌릴 수 없다.</b> */
    OUTBOUND
}
