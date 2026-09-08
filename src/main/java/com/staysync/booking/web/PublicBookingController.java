package com.staysync.booking.web;

import com.staysync.booking.PublicBookingService;
import com.staysync.booking.PublicBookingService.HoldResult;
import com.staysync.booking.PublicBookingService.PublicUnit;
import com.staysync.booking.domain.StayPeriod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 직접예약 위젯의 공개 API. 계획서 8.7.
 *
 * <p><b>{@code /public} 아래에 있다.</b> iCal 발행과 같은 자리이고, 로그인 없이 열린다.
 * 그래서 {@code AuthenticatedUser} 를 읽는 코드가 이 클래스에 하나도 없다 — 주체가
 * 없는 경로에서 그걸 읽으면 예외가 난다.
 *
 * <p><b>클라이언트가 보내는 것은 숙소·판매 단위 식별자와 날짜, 그리고 화면에 떠 있던
 * 금액뿐이다.</b> 나머지는 서버가 다시 구한다. 금액은 신뢰해서 쓰는 값이 아니라
 * <b>대조용</b>이다 — 다르면 거절한다.
 *
 * <h2>속도 제한이 없다. 열린 구멍이고 알고 남긴다</h2>
 *
 * <p>{@code POST /hold} 는 로그인 없이 재고를 15분씩 잡는다. 스크립트로 반복하면 그
 * 숙소의 판매 가능 날짜를 계속 비워 둘 수 있다. 결제까지 가지 않아도 된다.
 *
 * <p>작업지시 13 의 5절 4번은 <b>3주차 로그인 시도 제한을 재사용</b>하되 재사용이
 * 안 되면 이번 주에 하지 말라고 정했다. 확인해 보니 재사용이 안 된다.
 *
 * <ol>
 *   <li><b>모듈 경계.</b> {@code LoginAttemptLimiter} 는 {@code identity.security} 에
 *       있는 내부 타입이다. booking 이 참조하면 {@code ModularityTest} 가 깨진다.
 *       지금 identity 를 참조하는 모듈은 하나도 없다</li>
 *   <li><b>성격이 다르다.</b> 그것은 <b>실패</b>를 세어 잠그는 장치다(15분에 5회 실패
 *       → 10분 잠금). 여기에 겨누려면 <b>성공한 요청</b>을 실패로 세어야 하고, 그러면
 *       날짜를 몇 번 바꿔 본 정상 손님이 10분 동안 예약을 못 한다</li>
 *   <li><b>상수가 로그인과 공유된다.</b> 창과 상한이 {@code static final} 이라
 *       위젯 쪽을 조정하면 로그인 잠금이 함께 바뀐다</li>
 * </ol>
 *
 * <p>셋을 넘으려면 속도 제한기를 새로 세워야 하고, 그건 축소 순서 1번 기능에
 * 인프라를 더하는 일이다. <b>만들지 않았다.</b> 계획서 15.1 에 미조치 항목으로 적었다.
 *
 * <p>덧붙여, 넣었더라도 구멍이 닫히지는 않는다. <b>IP 는 바꿀 수 있다.</b> 얇은 한
 * 겹이었을 것이고 그렇게 적는다.
 */
@RestController
@RequestMapping("/public/booking")
class PublicBookingController {

    private final PublicBookingService service;

    PublicBookingController(PublicBookingService service) {
        this.service = service;
    }

    @GetMapping("/{propertyId}/availability")
    List<PublicUnit> availability(
            @PathVariable Long propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.availability(propertyId, from, to);
    }

    /**
     * 임시 점유를 만든다. 결제창은 이 응답의 금액으로 연다.
     *
     * <p>201 이다. 만들어진 것이 있고, 15분 뒤에 사라진다.
     */
    @PostMapping("/{propertyId}/hold")
    ResponseEntity<HoldResult> hold(@PathVariable Long propertyId,
                                    @Valid @RequestBody HoldRequest request) {
        HoldResult result = service.hold(propertyId, request.unitId(),
                new StayPeriod(request.checkIn(), request.checkOut()),
                request.quotedAmount(), request.adults(), request.children(),
                request.guestName(), request.guestPhone(), request.guestEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * 위젯이 보내는 것.
     *
     * @param quotedAmount 화면에 떠 있던 금액. <b>이 값으로 예약을 만들지 않는다.</b>
     *                     서버 재계산과 대조하고 다르면 거절한다
     */
    record HoldRequest(
            @NotNull Long unitId,
            @NotNull LocalDate checkIn,
            @NotNull LocalDate checkOut,
            @NotNull BigDecimal quotedAmount,
            @Min(1) @Max(30) short adults,
            @Min(0) @Max(30) short children,
            @NotBlank @Size(max = 120) String guestName,
            @Size(max = 40) String guestPhone,
            @Size(max = 200) String guestEmail) {
    }
}
