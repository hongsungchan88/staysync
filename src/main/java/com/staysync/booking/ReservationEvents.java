package com.staysync.booking;

import com.staysync.booking.domain.Reservation;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 예약 이벤트와 감사 기록의 이름, 그리고 페이로드를 만드는 곳.
 *
 * <p>페이로드를 만드는 자리를 한곳에 모은 이유는 <b>개인정보를 넣지 않는다는 규칙을
 * 지킬 지점을 하나로 만들기 위해서다.</b> 전이마다 맵을 손으로 만들면 어딘가에서
 * 게스트 이름이나 연락처가 섞여 들어간다. 게스트 연락처를 암호화해 놓고 이벤트에
 * 평문으로 실으면 암호화를 우회하는 두 번째 경로가 된다(ADR 0007 결과 절).
 *
 * <p>여기서 만드는 맵에는 <b>식별자와 상태, 날짜, 금액만</b> 들어간다.
 * {@code guestId} 는 담지만 이름은 담지 않는다. 소비자가 값이 필요하면 그 식별자로
 * 조회해 복호화한다.
 */
final class ReservationEvents {

    static final String AGGREGATE_TYPE = "RESERVATION";

    /** 바깥이 알아야 하는 사건들. 체크인은 아직 소비자가 없어 이벤트를 만들지 않는다. */
    static final String CONFIRMED = "RESERVATION_CONFIRMED";

    /**
     * 임시 점유가 생겼다. <b>재고가 줄었다는 뜻이다.</b> P4 16주차에 더했다.
     *
     * <p>{@code available() = total - booked - held} 이므로 HOLD 는 채널에 나갈 재고를
     * 곧바로 줄인다. 그런데 {@link #EXPIRED} 만 내보내고 있었다 — <b>홀드 생애주기에서
     * 재고가 느는 쪽만 알리고 줄어드는 쪽은 알리지 않는 비대칭</b>이었다. 만료만
     * 내보내던 것을 생성까지 맞춘다.
     *
     * <p>채널은 홀드의 존재를 알지 못한다. {@code ChannelSyncService} 가 페이로드가
     * 아니라 현재 원장을 다시 읽으므로 숫자만 받는다.
     */
    static final String HELD = "RESERVATION_HELD";
    static final String CANCELLED = "RESERVATION_CANCELLED";
    static final String DATES_CHANGED = "RESERVATION_DATES_CHANGED";
    static final String CHECKED_OUT = "RESERVATION_CHECKED_OUT";
    static final String EXPIRED = "RESERVATION_EXPIRED";

    private ReservationEvents() {
    }

    /**
     * 이벤트 페이로드.
     *
     * <p>채널이 재고와 상태를 맞추는 데 필요한 것만 담는다. 이름과 연락처는 없다.
     */
    static Map<String, Object> payloadOf(Reservation reservation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", reservation.getId());
        payload.put("propertyId", reservation.getPropertyId());
        payload.put("unitId", reservation.getUnitId());
        payload.put("guestId", reservation.getGuestId());     // 식별자만. 이름은 넣지 않는다
        payload.put("confirmationCode", reservation.getConfirmationCode());
        payload.put("channelCode", reservation.getChannelCode());
        payload.put("status", reservation.getStatus().name());
        payload.put("checkIn", reservation.getPeriod().checkIn().toString());
        payload.put("checkOut", reservation.getPeriod().checkOut().toString());
        payload.put("totalAmount", reservation.getTotalAmount());
        return payload;
    }

    /**
     * 판매 단위를 옮겼을 때 <b>옛 단위</b>의 재고가 늘었다는 사실을 알리는 페이로드.
     *
     * <p>{@link #payloadOf} 는 예약이 지금 붙어 있는 단위만 담는다. 업그레이드 배정은
     * 두 단위의 재고를 함께 바꾸므로 이벤트가 하나면 <b>옛 단위의 늘어난 재고가 어느
     * 채널에도 나가지 않는다.</b> 팔 수 있는 방을 못 파는 상태가 되고, 우리 쪽 로그는
     * 전부 정상이다.
     */
    static Map<String, Object> releasedUnitPayload(Reservation reservation, Long releasedUnitId) {
        Map<String, Object> payload = payloadOf(reservation);
        payload.put("unitId", releasedUnitId);
        // 옛 단위에서는 예약이 사라진 것이므로 취소와 같은 사건으로 읽혀야 한다.
        payload.put("status", "CANCELLED");
        return payload;
    }

    /** 감사 기록의 전후 값. 상태와 날짜만 담는다. */
    static Map<String, Object> auditSnapshot(String status, String checkIn, String checkOut) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status);
        snapshot.put("checkIn", checkIn);
        snapshot.put("checkOut", checkOut);
        return snapshot;
    }

    static Map<String, Object> auditSnapshot(Reservation reservation) {
        return auditSnapshot(
                reservation.getStatus().name(),
                reservation.getPeriod().checkIn().toString(),
                reservation.getPeriod().checkOut().toString());
    }
}
