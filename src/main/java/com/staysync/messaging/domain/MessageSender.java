package com.staysync.messaging.domain;

/** 메시지를 쓴 주체. {@code chk_msg_sender} 와 짝이다. */
public enum MessageSender {

    GUEST,

    /** 사람이 쓴 답. 인박스에서 보낸 것이 이것이다. */
    HOST,

    /** AI 가 쓴 초안을 사람이 승인해 보낸 것. P5 에서 채워진다. */
    AI,

    /** 자동 발송 규칙이 만든 것. 사람이 쓰지 않았다는 사실이 화면에 드러나야 한다. */
    SYSTEM
}
