package com.staysync.channel.domain;

/** 동기화 작업의 종류. {@code sync_job.job_type} 과 짝이다. */
public enum SyncJobType {

    /** 우리 → 채널. 재고·요금·제약을 보낸다. */
    PUSH_ARI,

    /** 채널 → 우리. 예약을 긁어 온다. 12주차의 Mock 폴링이 쓴다. */
    PULL_BOOKING,

    /** 채널 → 우리. iCal 폴링이다. 13주차다. */
    PULL_ICAL,

    /**
     * 우리 → 채널. 메시지를 보낸다. P4 14주차다.
     *
     * <p>메시지 전용 큐를 만들지 않고 여기에 얹었다. 재시도·백오프·{@code DEAD} 와
     * 연결별 순서 보장이 전부 이미 여기 있고, 두 벌로 나누면 갈라지는 순간
     * "어떤 메시지는 재시도되고 어떤 메시지는 사라진다"가 된다(ADR 0012 결과 절).
     */
    SEND_MESSAGE
}
