package com.staysync.channel.domain;

/** 동기화 작업의 상태. {@code chk_syncjob_status} 와 짝이다. */
public enum SyncJobStatus {

    /** 집어 가기를 기다린다. {@code next_run_at} 이 지나야 대상이 된다. */
    PENDING,

    /** 워커가 집어 갔다. */
    RUNNING,

    SUCCESS,

    /** 지금은 쓰지 않는다. 재시도 대상은 다시 {@link #PENDING} 으로 돌린다. */
    FAILED,

    /**
     * 더 시도하지 않는다. 재시도 상한에 닿았거나 영구 오류다.
     *
     * <p><b>행을 지우지 않는다.</b> {@code last_error} 가 남아 있어야 사람이 원인을 보고
     * 다시 넣을 수 있다. Outbox 재시도 상한과 같은 판단이다.
     */
    DEAD
}
