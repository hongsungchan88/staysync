package com.staysync.channel.adapter.ical;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 5545 발행물에서 {@code VEVENT} 를 꺼낸다.
 *
 * <p><b>명세는 docs/조사-02-에어비앤비-ical.md 3절이다.</b> 실제 에어비앤비가 발행한
 * 샘플이 {@code src/test/resources/ical/airbnb-unpublished.ics} 에 있고, 그 표가
 * 이 파서가 지켜야 할 것을 확정해 준다. 추측으로 만들지 않았다.
 *
 * <p><b>ical4j 를 쓰지 않는다.</b> 우리가 읽어야 하는 것은 {@code UID},
 * {@code DTSTART}, {@code DTEND}, {@code SUMMARY} 넷뿐이고, 그중 날짜 둘은
 * {@code VALUE=DATE} 다. 라이브러리 하나를 들이는 것보다 접는 줄을 펴고 네 속성을
 * 읽는 편이 짧다. 계획서 13.1 의 의존성 표에 ical4j 가 적혀 있지만, 실제로 필요한
 * 표면이 이만큼이라 넣지 않았다.
 *
 * <p>ponytail: {@code VALUE=DATE} 와 UTC {@code DATETIME} 만 읽는다. 시간대
 * 식별자가 붙은 {@code DTSTART;TZID=...} 는 날짜 부분만 취해 하루가 어긋날 수 있다.
 * 에어비앤비도 Channex 도 그렇게 발행하지 않으므로 지금은 문제가 없고, 그런 채널이
 * 생기면 ical4j 로 바꾼다.
 */
final class IcalParser {

    private IcalParser() {
    }

    /**
     * 하나의 일정.
     *
     * @param endExclusive {@code DTEND} 를 <b>그대로</b> 담는다. iCal 의
     *                     {@code DTEND} 는 배타적이라 체크아웃일과 같은 값이다.
     *                     {@code DTEND:20270906} 은 9월 5일까지 막힌 것이다
     */
    record VEvent(String uid, LocalDate start, LocalDate endExclusive, String summary) {
    }

    /**
     * 발행물을 읽는다.
     *
     * <p>필수 속성이 빠진 일정은 건너뛴다. 발행물 하나가 통째로 실패하는 것보다
     * 읽을 수 있는 것을 읽는 편이 낫다 — 통째로 실패하면 대량 소실 방어가 걸려
     * 취소가 보류되고, 그 상태가 계속된다.
     */
    static List<VEvent> parse(String body) {
        List<VEvent> events = new ArrayList<>();
        String uid = null;
        String summary = null;
        LocalDate start = null;
        LocalDate end = null;
        boolean inEvent = false;

        for (String line : unfold(body)) {
            if (line.equals("BEGIN:VEVENT")) {
                inEvent = true;
                uid = null;
                summary = null;
                start = null;
                end = null;
                continue;
            }
            if (line.equals("END:VEVENT")) {
                if (inEvent && uid != null && start != null && end != null && end.isAfter(start)) {
                    events.add(new VEvent(uid, start, end, summary == null ? "" : summary));
                }
                inEvent = false;
                continue;
            }
            if (!inEvent) {
                continue;
            }

            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            // 이름과 파라미터가 세미콜론으로 붙는다: DTSTART;VALUE=DATE:20260903
            String head = line.substring(0, colon);
            String value = line.substring(colon + 1);
            int semicolon = head.indexOf(';');
            String name = (semicolon < 0 ? head : head.substring(0, semicolon))
                    .toUpperCase(java.util.Locale.ROOT);

            switch (name) {
                case "UID" -> uid = value.trim();
                case "SUMMARY" -> summary = unescapeText(value);
                case "DTSTART" -> start = toLocalDate(value);
                case "DTEND" -> end = toLocalDate(value);
                default -> {
                    // 나머지 속성은 우리가 쓰지 않는다.
                }
            }
        }
        return events;
    }

    /**
     * 접힌 줄을 편다. RFC 5545 는 75옥텟에서 줄을 끊고 다음 줄을 공백이나 탭으로
     * 시작한다.
     *
     * <p>펴지 않으면 긴 {@code UID} 가 두 조각이 나고, 그러면 <b>같은 예약이 폴링마다
     * 다른 식별자로 들어와 예약이 무한히 늘어난다.</b> 우리 픽스처의 UID 는 짧아
     * 접히지 않지만, 다른 발행자는 접는다.
     */
    private static List<String> unfold(String body) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : body.split("\r\n|\n|\r", -1)) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
                current.append(raw, 1, raw.length());
                continue;
            }
            if (current.length() > 0) {
                lines.add(current.toString());
            }
            current.setLength(0);
            current.append(raw);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    /** {@code 20260903} 또는 {@code 20260903T024836Z}. 날짜 부분만 쓴다. */
    private static LocalDate toLocalDate(String value) {
        String digits = value.trim();
        int t = digits.indexOf('T');
        if (t >= 0) {
            digits = digits.substring(0, t);
        }
        if (digits.length() != 8) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(digits.substring(0, 4)),
                    Integer.parseInt(digits.substring(4, 6)),
                    Integer.parseInt(digits.substring(6, 8)));
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }

    /** TEXT 값의 이스케이프를 되돌린다. 역슬래시 뒤의 쉼표·세미콜론·n·역슬래시. */
    private static String unescapeText(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n', 'N' -> out.append('\n');
                case ',', ';', '\\' -> out.append(next);
                default -> out.append('\\').append(next);
            }
        }
        return out.toString();
    }
}
