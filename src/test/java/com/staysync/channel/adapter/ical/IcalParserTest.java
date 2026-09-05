package com.staysync.channel.adapter.ical;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>완료 조건 3.</b> 저장소의 실제 픽스처를 파싱한다.
 *
 * <p>{@code src/test/resources/ical/airbnb-unpublished.ics} 는 실제 에어비앤비가
 * 발행한 것이고, 조사-02 3절의 표가 이 파서의 명세다. 추측으로 만든 픽스처가 아니라
 * 진짜 발행물이라 <b>여기서 통과하면 실제 피드에서도 통과한다.</b>
 *
 * <p>스프링을 띄우지 않는다. 파싱은 순수 함수다.
 */
class IcalParserTest {

    @Test
    @DisplayName("실제 에어비앤비 발행물에서 VEVENT 하나를 읽는다")
    void 실제_픽스처를_파싱한다() throws IOException {
        List<IcalParser.VEvent> events = IcalParser.parse(fixture());

        assertThat(events).hasSize(1);
        IcalParser.VEvent event = events.get(0);
        assertThat(event.uid()).isEqualTo("7f662ec65913-...@airbnb.com");
        assertThat(event.summary()).isEqualTo("Airbnb (Not available)");
    }

    @Test
    @DisplayName("DTEND 는 배타적이다. 20270906 이면 9월 5일까지 막힌 것이다")
    void DTEND_를_배타적으로_읽는다() throws IOException {
        IcalParser.VEvent event = IcalParser.parse(fixture()).get(0);

        assertThat(event.start()).isEqualTo(LocalDate.of(2026, 9, 3));

        // 여기가 이 주의 가장 위험한 자리다. 포함으로 읽어 하루를 빼면 모든 예약이
        // 하루씩 밀리고, 화면에서는 정상으로 보인다(조사-02 3절).
        assertThat(event.endExclusive())
                .as("DTEND 를 그대로 체크아웃일로 쓴다")
                .isEqualTo(LocalDate.of(2027, 9, 6));
        assertThat(event.endExclusive().minusDays(1))
                .as("실제로 막힌 마지막 밤")
                .isEqualTo(LocalDate.of(2027, 9, 5));
    }

    @Test
    @DisplayName("접힌 줄을 펴서 읽는다")
    void 접힌_줄을_편다() {
        // RFC 5545 는 75옥텟에서 줄을 끊고 다음 줄을 공백으로 시작한다. 펴지 않으면
        // 긴 UID 가 두 조각이 나고, 같은 예약이 폴링마다 다른 식별자로 들어와
        // 예약이 무한히 늘어난다.
        String body = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:20261001
                DTEND;VALUE=DATE:20261004
                UID:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                 bbbbbbbbbbbbbbbbbbbb@airbnb.com
                SUMMARY:Airbnb (Not available)
                END:VEVENT
                END:VCALENDAR
                """;

        List<IcalParser.VEvent> events = IcalParser.parse(body);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).uid())
                .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbb@airbnb.com");
    }

    @Test
    @DisplayName("필수 속성이 빠진 일정은 건너뛰고 나머지를 읽는다")
    void 깨진_일정_하나가_발행물_전체를_버리게_하지_않는다() {
        // 통째로 실패하면 대량 소실 방어가 걸려 취소가 계속 보류된다.
        String body = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:20261001
                SUMMARY:UID 가 없다
                END:VEVENT
                BEGIN:VEVENT
                DTSTART;VALUE=DATE:20261010
                DTEND;VALUE=DATE:20261012
                UID:good@airbnb.com
                END:VEVENT
                END:VCALENDAR
                """;

        assertThat(IcalParser.parse(body))
                .extracting(IcalParser.VEvent::uid)
                .containsExactly("good@airbnb.com");
    }

    @Test
    @DisplayName("DTSTART 가 UTC 시각형이어도 날짜로 읽는다")
    void 시각형_날짜도_읽는다() {
        String body = """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                DTSTART:20261001T150000Z
                DTEND:20261004T110000Z
                UID:datetime@example.com
                END:VEVENT
                END:VCALENDAR
                """;

        IcalParser.VEvent event = IcalParser.parse(body).get(0);

        assertThat(event.start()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(event.endExclusive()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    private static String fixture() throws IOException {
        try (InputStream in = IcalParserTest.class
                .getResourceAsStream("/ical/airbnb-unpublished.ics")) {
            assertThat(in).as("실제 에어비앤비 발행물 픽스처").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
