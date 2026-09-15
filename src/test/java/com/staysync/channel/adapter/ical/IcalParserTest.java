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

    // --- 게시 리스팅 (P5 17주차, 업체 실제 피드) --------------------------------

    @Test
    @DisplayName("게시 리스팅 발행물은 예약마다 VEVENT 하나고 SUMMARY 가 둘로 갈린다")
    void 게시_피드는_예약마다_이벤트_하나다() throws IOException {
        List<IcalParser.VEvent> events = IcalParser.parse(fixture("/ical/airbnb-published.ics"));

        // 미게시 피드(1년치 VEVENT 하나)와 처음으로 다른 자리다(조사-02).
        assertThat(events).hasSize(15);
        assertThat(events).filteredOn(e -> e.summary().equals("Reserved")).hasSize(14);
        assertThat(events).filteredOn(e -> e.summary().equals("Airbnb (Not available)"))
                .as("호스트 차단이든 1년 창의 끝이든, 예약이 아닌 이벤트가 섞여 온다")
                .hasSize(1);
        // 어댑터가 Reserved 만 예약으로 옮기려면 SUMMARY 가 여기까지 살아 있어야 한다.
        assertThat(events).allSatisfy(e -> assertThat(e.summary()).isNotBlank());
    }

    @Test
    @DisplayName("게시 피드의 접힌 DESCRIPTION 이 뒤따르는 UID·날짜를 삼키지 않는다")
    void 접힌_DESCRIPTION_뒤의_속성이_살아있다() throws IOException {
        List<IcalParser.VEvent> events = IcalParser.parse(fixture("/ical/airbnb-published.ics"));

        // 실제 발행물은 DESCRIPTION 이 75옥텟에서 접혀 두 줄이다. 펴기가 틀리면 그 뒤
        // 속성이 DESCRIPTION 값에 붙어 UID 가 비고 이벤트가 통째로 빠진다.
        IcalParser.VEvent first = events.get(0);
        assertThat(first.uid()).matches("[0-9a-f]{12}-[0-9a-f]{32}@airbnb\\.com");
        assertThat(first.start()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(first.endExclusive()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(events).extracting(IcalParser.VEvent::uid).doesNotHaveDuplicates();
    }

    private static String fixture() throws IOException {
        return fixture("/ical/airbnb-unpublished.ics");
    }

    private static String fixture(String path) throws IOException {
        try (InputStream in = IcalParserTest.class.getResourceAsStream(path)) {
            assertThat(in).as("실제 에어비앤비 발행물 픽스처 " + path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
