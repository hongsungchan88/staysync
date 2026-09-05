package com.mockota;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * 시뮬레이터가 만들어 낸 예약을 쌓아 둔다. 폴링 조회가 이걸 읽는다.
 *
 * <p><b>중복도, 순서 역전도 그대로 담는다.</b> 같은 {@code bookingId} 를 두 번 넣으면
 * 두 건이 되고, {@code revision} 2 다음에 1 이 들어오면 그 순서로 남는다.
 *
 * <p>여기서 막고 싶은 마음이 들면 방향을 거꾸로 잡은 것이다. <b>막는 것은 우리 쪽
 * 일이고</b>({@code (channel_code, channel_booking_id)} 유니크 제약, revision 비교),
 * 그게 실제로 막는지 보려고 나쁘게 구는 상대가 필요해서 이 시뮬레이터를 만든다.
 * 여기서 바로잡으면 12주차에 검증할 것이 사라진다.
 */
@Component
public class BookingStore {

    private final List<MockBooking> bookings = new CopyOnWriteArrayList<>();

    public void add(MockBooking booking) {
        bookings.add(booking);
    }

    /** 넣은 순서 그대로. 순서 역전 시나리오를 확인하려면 이 순서가 보존돼야 한다. */
    public List<MockBooking> all() {
        return List.copyOf(bookings);
    }

    public void clear() {
        bookings.clear();
    }
}
