package com.staysync.booking;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * booking 이 예약 조회를 위해 공개하는 통로. <b>읽기 전용이다.</b>
 *
 * <p>messaging 이 쓴다. 스레드를 예약에 잇고, 템플릿 변수를 채우고, 시간 기반 자동
 * 발송이 대상을 고를 때 필요하다. 의존 방향은 messaging → booking 이다.
 *
 * <p>{@code ChannelBookingIntake} 와 나눈 이유는 성격이다. 저쪽은 쓰기이고 락 경계가
 * 붙어 있다. 여기는 조회뿐이라 락이 필요 없고, 한 인터페이스에 섞으면 읽기만 하려는
 * 쪽이 쓰기 메서드까지 보게 된다.
 */
public interface ReservationDirectory {

    Optional<ReservationBrief> find(Long reservationId);

    /**
     * 채널 측 예약번호로 찾는다.
     *
     * <p>채널 메시지에는 그 채널의 예약번호가 붙어 온다. 우리 예약 식별자를 모르는
     * 상태에서 스레드를 예약에 이으려면 이 경로가 필요하다.
     */
    Optional<ReservationBrief> findByChannel(String channelCode, String channelBookingId);

    /** 여러 건을 한 번에. 스레드 목록이 예약마다 조회하면 왕복이 스레드 수만큼 는다. */
    List<ReservationBrief> findAll(Collection<Long> reservationIds);

    /**
     * 그날 체크인하는, 아직 살아 있는 예약.
     *
     * <p><b>취소·만료된 예약은 나오지 않는다.</b> 체크인 하루 전 알림이 취소된 예약에
     * 나가는 것이 이 기능의 가장 흔한 사고이고, 나간 뒤에는 되돌릴 수 없다.
     */
    List<ReservationBrief> activeByCheckIn(LocalDate date);

    /** 그날 체크아웃하는, 아직 살아 있는 예약. 위와 같은 이유로 취소된 것은 빠진다. */
    List<ReservationBrief> activeByCheckOut(LocalDate date);
}
