package com.staysync.messaging;

/**
 * messaging 이 바깥에 공개하는 알림 통로. P4 15주차에 열었다.
 *
 * <p><b>채널로 나가지 않는다.</b> 인박스 스레드에 시스템 메시지로만 남는다.
 * 웹푸시는 서비스 워커와 구독 관리와 키가 따라오고 알림톡은 사업자 등록과 발신
 * 프로필 심사가 필요하다 — 둘 다 이 프로젝트에서 확보할 수 없는 것 위에 있다.
 * 14주차에 만들어 둔 인박스를 쓰면 새 경로 없이 담당자가 볼 곳이 생긴다
 * (작업지시 12 의 5절 2번).
 *
 * <p>ops 가 쓴다. 의존 방향은 ops → messaging 이다.
 */
public interface InboxNotes {

    /**
     * 그 예약의 대화에 알림을 남긴다. 스레드가 없으면 만든다.
     *
     * <p>예약을 찾을 수 없으면 아무것도 하지 않고 {@code false} 다. 알림이 붙지 못한
     * 것이 태스크 생성을 되돌릴 이유는 아니다 — 청소는 여전히 필요하다.
     *
     * @return 실제로 남겼으면 true
     */
    boolean note(Long reservationId, String body);
}
