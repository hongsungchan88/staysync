package com.staysync.channel.domain;

/** 동기화 작업의 종류. {@code sync_job.job_type} 과 짝이다. */
public enum SyncJobType {

    /** 우리 → 채널. 재고·요금·제약을 보낸다. */
    PUSH_ARI,

    /** 채널 → 우리. 예약을 긁어 온다. 12주차의 Mock 폴링이 쓴다. */
    PULL_BOOKING,

    /** 채널 → 우리. iCal 폴링이다. 13주차다. */
    PULL_ICAL
}
