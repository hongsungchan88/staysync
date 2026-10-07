package com.staysync.identity.domain;

import com.staysync.shared.error.DomainException;

/**
 * 비밀번호 변경에서 현재 비밀번호가 틀렸다.
 *
 * <p><b>401 이 아니라 400 이다.</b> 로그인한 사용자의 요청이라 401 을 주면 화면이 세션 만료로 읽고 갱신을
 * 시도한 뒤 로그아웃시킨다. 틀린 것은 세션이 아니라 입력 한 칸이다. 시도 횟수는 로그인 실패와 같은 제한에
 * 센다({@code LoginAttemptLimiter}).
 */
public class CurrentPasswordMismatchException extends DomainException {

    public CurrentPasswordMismatchException() {
        super("CURRENT_PASSWORD_MISMATCH", "현재 비밀번호가 맞지 않습니다.");
    }
}
