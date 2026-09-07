package com.staysync.ops.domain;

/**
 * 태스크 종류.
 *
 * <p><b>{@code CLEANING} 하나뿐이다.</b> V1 의 체크 제약에는 정비·점검도 있지만
 * 만들 이유가 아직 없다(작업지시 12 의 3절). 쓰지 않는 값을 미리 열어 두면 어느
 * 것이 실제로 도는지 알 수 없어진다.
 */
public enum TaskType {
    CLEANING
}
