package com.mockota;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 채널의 표면. 12주차 워커와 어댑터가 여기를 두드린다.
 *
 * <p>악조건 주입({@link ChaosFilter})이 걸리는 유일한 경로이기도 하다. 시나리오
 * 엔드포인트는 채널의 표면이 아니라 테스트가 쥐는 손잡이라 지나간다.
 */
@RestController
@RequestMapping("/api")
class ChannelController {

    private final AriStore ari;
    private final BookingStore bookings;

    ChannelController(AriStore ari, BookingStore bookings) {
        this.ari = ari;
        this.bookings = bookings;
    }

    /**
     * ARI 를 받는다. <b>해석하지 않고 그대로 담는다.</b>
     *
     * <p>202 로 답한다. 실제 채널 매니저가 그렇듯 받았다는 것과 반영했다는 것은 다르고,
     * 200 으로 답하면 12주차 워커가 "반영됐다"로 읽을 여지가 생긴다.
     */
    @PostMapping("/ari")
    ResponseEntity<AriStore.Entry> receiveAri(@RequestBody JsonNode body) {
        return ResponseEntity.accepted().body(ari.append(body));
    }

    /** 보낸 것을 그대로 되돌려 준다. 12주차가 "무엇을 보냈나"를 여기서 확인한다. */
    @GetMapping("/ari")
    List<AriStore.Entry> listAri() {
        return ari.all();
    }

    /**
     * 받은 것을 전부 잃어버린다.
     *
     * <p>12주차의 "전송한 ARI 무시"가 이 경로다. 채널이 받았다고 답해 놓고 반영하지
     * 않은 상황을 만들고, 재동기화 배치가 그걸 다시 채우는지 본다.
     */
    @DeleteMapping("/ari")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void forgetAri() {
        ari.clear();
    }

    /** 폴링 수신 경로. 만들어 둔 예약을 넣은 순서 그대로 돌려준다. */
    @GetMapping("/bookings")
    List<MockBooking> listBookings() {
        return bookings.all();
    }
}
