package com.staysync.channel.port;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 채널이 지금 들고 있다고 답한 하루치 값. 정기 재동기화(계획서 6.6)가 쓴다.
 *
 * <p>우리가 보낸 것이 아니라 <b>채널이 실제로 반영한 것</b>이다. 둘이 갈리는 것이
 * 재동기화가 있는 이유다 — 받았다고 답해 놓고 반영하지 않는 채널, 메모리 버퍼에
 * 있다가 앱과 함께 사라진 6초, {@code RUNNING} 인 채 유실된 작업이 전부 여기서
 * 드러난다.
 *
 * <p>{@code null} 은 "그 값은 모른다"는 뜻이고 대조에서 건너뛴다. 채널마다 돌려주는
 * 항목이 달라서다 — 모르는 것을 다르다고 판정하면 매일 새벽에 전 기간을 다시 보내게
 * 된다.
 */
public record ChannelAriDay(LocalDate date,
                            Integer availability,
                            BigDecimal rate,
                            Integer minStay,
                            Boolean stopSell) {

    public ChannelAriDay {
        if (date == null) {
            throw new IllegalArgumentException("날짜는 필수입니다.");
        }
    }
}
