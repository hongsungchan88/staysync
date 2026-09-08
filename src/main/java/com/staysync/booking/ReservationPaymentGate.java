package com.staysync.booking;

/**
 * 결제가 끝난 예약을 확정으로 올리는 통로. <b>쓰기다.</b>
 *
 * <p>payment 가 쓴다. 의존 방향은 payment → booking 이다.
 *
 * <p>{@link ReservationDirectory} 와 나눈 이유는 성격이다. 저쪽은 "읽기 전용이다"라고
 * 못 박아 둔 인터페이스이고, 한곳에 섞으면 조회만 하려는 messaging 이 예약을 확정하는
 * 메서드까지 손에 쥔다. {@code ChannelBookingIntake} 를 따로 둔 것과 같은 선이다.
 *
 * <p><b>확정 자체는 booking 안에서 일어난다.</b> payment 가 상태 전이를 직접 하지
 * 않는다 — 확정은 재고 승격(HOLD → 확정 점유)을 함께 해야 하고, 그건 락 경계 안의
 * 일이라 booking 밖으로 꺼낼 수 없다.
 */
public interface ReservationPaymentGate {

    /**
     * 결제가 확인된 홀드를 확정으로 올린다.
     *
     * <p><b>멱등하다.</b> 같은 웹훅이 두 번 와도 두 번째는 아무 일도 하지 않는다.
     * 포트원 웹훅은 최소 1회 전달이라 재전송이 정상 동작이고, 두 번째에 예외를 던지면
     * 결제는 됐는데 웹훅 응답이 실패로 남아 포트원이 계속 재시도한다.
     *
     * <p>상태를 <b>먼저 조회해 갈린다.</b> 이미 확정이면 그대로 둔다 — 다시 확정하면
     * 재고를 두 번 승격해 원장이 틀어진다. 예외로 흐름을 제어하지 않는다는 규칙이
     * 그대로 적용되는 자리다(작업지시 13 의 4절).
     *
     * @return 이번 호출이 실제로 확정을 올렸으면 {@code true}. 이미 확정이었으면
     *         {@code false}
     * @throws ReservationNotFoundException 그 예약이 없을 때
     */
    boolean confirmPaid(Long reservationId);
}
