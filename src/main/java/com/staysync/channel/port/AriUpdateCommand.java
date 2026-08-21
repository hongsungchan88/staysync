package com.staysync.channel.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 채널에 보낼 재고·요금·제약 변경 묶음.
 *
 * <p>연속된 날짜에 같은 값이 반복되면 구간으로 압축해 담는다. 캘린더에서 30일을
 * 드래그해 요금을 바꿔도 채널당 요청이 한 번으로 끝나게 하기 위해서다.
 */
public record AriUpdateCommand(String externalUnitId,
                               String externalRateId,
                               List<Segment> segments) {

    /**
     * 하나의 날짜 구간에 적용할 값. null 인 항목은 변경하지 않는다는 뜻이다.
     */
    public record Segment(LocalDate from,
                          LocalDate to,
                          Integer availability,
                          BigDecimal rate,
                          Integer minStay,
                          Integer maxStay,
                          Boolean closedToArrival,
                          Boolean closedToDeparture,
                          Boolean stopSell) {

        public Segment {
            if (from == null || to == null || to.isBefore(from)) {
                throw new IllegalArgumentException("날짜 구간이 올바르지 않습니다: " + from + " ~ " + to);
            }
        }

        public long days() {
            return to.toEpochDay() - from.toEpochDay() + 1;
        }
    }
}
