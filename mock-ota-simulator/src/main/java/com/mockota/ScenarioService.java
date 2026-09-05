package com.mockota;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * 예약을 만들어 내는 시나리오 넷.
 *
 * <p><b>시뮬레이터는 나쁘게 굴어야 한다.</b> 중복을 막거나 순서를 바로잡으면 12주차에
 * 검증할 것이 사라진다. 여기의 모든 메서드는 실제 OTA 가 실제로 저지르는 일을
 * 재현하는 것이고, 그것을 우리 쪽이 견디는지 보는 것이 목적이다.
 */
@Service
public class ScenarioService {

    /** 동시 예약 다발이 서로를 기다리는 시간의 상한. 넘으면 시나리오 자체가 고장난 것이다. */
    private static final int BARRIER_TIMEOUT_SECONDS = 10;

    private final BookingStore bookings;
    private final WebhookSender webhook;

    ScenarioService(BookingStore bookings, WebhookSender webhook) {
        this.bookings = bookings;
        this.webhook = webhook;
    }

    /**
     * @param maxConcurrent 실제로 겹친 최대 건수. 동시 예약 다발만 1보다 크다
     */
    public record ScenarioResult(String scenario, int emitted, int maxConcurrent,
                                 List<MockBooking> bookings) {
    }

    /**
     * 같은 예약을 여러 번 보낸다. <b>예약 번호까지 같다.</b>
     *
     * <p>실제 OTA 의 웹훅은 최소 1회 전달이라 같은 것이 두 번 세 번 온다. 우리 쪽의
     * {@code (channel_code, channel_booking_id)} 유니크 제약이 그걸 한 건으로 흡수하는지
     * 보려면 같은 번호로 여러 번 오는 상황이 필요하다.
     */
    public ScenarioResult duplicate(ScenarioRequest request) {
        List<MockBooking> emitted = new ArrayList<>();
        for (int i = 0; i < request.count(); i++) {
            emitted.add(emit(booking(request, request.bookingId(), 1)));
        }
        return new ScenarioResult("duplicate", emitted.size(), 1, emitted);
    }

    /**
     * 같은 객실·같은 날짜에 예약 여럿을 <b>동시에</b> 만든다.
     *
     * <p>재고 방어 4계층이 겨냥하는 상황이다. 순차로 보내면 락도 {@code FOR UPDATE} 도
     * 아무 일을 하지 않아 통과하고, 그 테스트는 아무것도 보증하지 않는다.
     *
     * <p>{@link CyclicBarrier} 로 전원이 도착할 때까지 붙잡아 둔다. 스레드를 띄워
     * 놓고 겹치기를 바라면 빠른 기계에서는 순차로 지나가 버린다. 이렇게 하면
     * {@code maxConcurrent == count} 가 <b>타이밍 운이 아니라 구조로</b> 보장된다.
     */
    public ScenarioResult burst(ScenarioRequest request) {
        int count = request.count();
        CyclicBarrier allArrived = new CyclicBarrier(count);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            List<Future<MockBooking>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String bookingId = request.bookingId() + "-" + (i + 1);
                futures.add(pool.submit(() -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        allArrived.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        return emit(booking(request, bookingId, 1));
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }));
            }
            List<MockBooking> emitted = new ArrayList<>();
            for (Future<MockBooking> future : futures) {
                emitted.add(await(future));
            }
            return new ScenarioResult("burst", emitted.size(), peak.get(), emitted);
        }
    }

    /**
     * 같은 예약의 수정본을 <b>높은 버전 먼저, 낮은 버전 나중에</b> 보낸다.
     *
     * <p>네트워크와 재시도 때문에 실제로 일어나는 순서 역전이다. 우리 쪽은 낮은 버전이
     * 나중에 와도 무시해야 하는데, 무시하지 않으면 취소된 예약이 되살아나거나 옛 날짜로
     * 되돌아간다. <b>화면에서는 정상으로 보이고</b> 드러나는 것은 재고가 어긋난 뒤다.
     */
    public ScenarioResult revisionReorder(ScenarioRequest request) {
        MockBooking base = booking(request, request.bookingId(), 1);
        List<MockBooking> emitted = new ArrayList<>();
        for (int revision = request.count(); revision >= 1; revision--) {
            emitted.add(emit(base.withRevision(revision)));
        }
        return new ScenarioResult("revision-reorder", emitted.size(), 1, emitted);
    }

    /**
     * 같은 객실·같은 날짜에 예약을 잔뜩 만든다. 재고가 몇이든 상관하지 않는다.
     *
     * <p><b>P3 의 핵심이다.</b> OTA 에서 이미 성사된 예약은 우리가 거절할 수 없다.
     * 재고가 없어도 받아들이고 {@code overbooking_conflict} 에 기록해 운영자가 푸는 것이
     * 정해 둔 처리이고, 그 상황을 만들 수 있는 것이 시뮬레이터뿐이다.
     *
     * <p>다발과 달리 순차로 보낸다. 여기서 보려는 것은 동시성이 아니라 <b>재고를 넘긴
     * 예약이 거절되지 않고 충돌로 남는가</b>이기 때문이다.
     */
    public ScenarioResult overbook(ScenarioRequest request) {
        List<MockBooking> emitted = new ArrayList<>();
        for (int i = 0; i < request.count(); i++) {
            emitted.add(emit(booking(request, request.bookingId() + "-" + (i + 1), 1)));
        }
        return new ScenarioResult("overbook", emitted.size(), 1, emitted);
    }

    /** 저장하고 웹훅을 쏜다. 폴링으로도 푸시로도 같은 예약이 보이게 하는 자리다. */
    private MockBooking emit(MockBooking booking) {
        bookings.add(booking);
        webhook.send(booking);
        return booking;
    }

    private static MockBooking booking(ScenarioRequest request, String bookingId, int revision) {
        return new MockBooking(bookingId, request.roomId(), request.checkIn(), request.checkOut(),
                request.guestName(), 2, 0, request.totalAmount(), revision,
                MockBooking.BOOKED, Instant.now());
    }

    private static MockBooking await(Future<MockBooking> future) {
        try {
            return future.get(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 예약 다발이 중단됐습니다.", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("동시 예약 다발이 제한 시간 안에 끝나지 않았습니다.", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof BrokenBarrierException) {
                throw new IllegalStateException("동시 예약 다발의 스레드 하나가 먼저 실패했습니다.", cause);
            }
            throw new IllegalStateException("동시 예약 다발이 실패했습니다.", cause);
        }
    }
}
