package com.staysync.channel.adapter.ical;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 우리 캘린더를 RFC 5545 발행물로 쓴다. 계획서 6.2 의 발행 쪽이다.
 *
 * <p><b>{@code DTEND} 를 배타적으로 쓴다.</b> 받을 때 배타적으로 읽고(조사-02 3절)
 * 낼 때 포함으로 쓰면 왕복에서 하루가 어긋난다. 3박 예약이면 체크아웃일이 그대로
 * {@code DTEND} 다. 이 어긋남은 화면에서 정상으로 보이고 재고가 틀어진 뒤에야
 * 드러난다.
 *
 * <p><b>게스트 이름을 넣지 않는다.</b> 이 URL 은 인증이 토큰 하나뿐이고 읽는 쪽은
 * 에어비앤비 서버다. {@code SUMMARY} 는 고정 문자열이면 상대가 날짜를 막는 데
 * 충분하다. ADR 0007 이 그은 선의 연장이다.
 */
public final class IcalWriter {

    /** 발행물의 모든 일정이 같은 문자열을 쓴다. 무엇이 막혔는지는 알리지 않는다. */
    static final String SUMMARY = "StaySync (Not available)";

    private static final String PRODID = "-//StaySync//Channel Calendar 1.0//EN";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private IcalWriter() {
    }

    /**
     * 막힌 구간 하나.
     *
     * @param endExclusive {@code DTEND} 에 그대로 들어간다. 마지막으로 막힌 날의
     *                     <b>다음 날</b>이며, 예약이라면 체크아웃일이다
     */
    public record BlockedRange(LocalDate start, LocalDate endExclusive) {

        public BlockedRange {
            if (start == null || endExclusive == null || !endExclusive.isAfter(start)) {
                throw new IllegalArgumentException("차단 구간이 올바르지 않습니다: " + start + " ~ " + endExclusive);
            }
        }
    }

    /**
     * @param uidSuffix {@code UID} 뒤에 붙는 도메인 자리. 발행자를 구분하는 값이며
     *                  토큰을 넣지 않는다 — 발행물이 그 자체로 URL 을 흘리게 된다
     */
    public static String write(List<BlockedRange> ranges, String uidSuffix) {
        StringBuilder out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "PRODID:" + PRODID);
        line(out, "VERSION:2.0");
        line(out, "CALSCALE:GREGORIAN");

        String stamp = java.time.OffsetDateTime.now(ZoneOffset.UTC).format(STAMP);
        for (BlockedRange range : ranges) {
            line(out, "BEGIN:VEVENT");
            line(out, "DTSTAMP:" + stamp);
            line(out, "DTSTART;VALUE=DATE:" + range.start().format(DATE));
            // 배타적이다. 마지막으로 막힌 날의 다음 날을 그대로 쓴다.
            line(out, "DTEND;VALUE=DATE:" + range.endExclusive().format(DATE));
            line(out, "SUMMARY:" + SUMMARY);
            line(out, "UID:" + range.start().format(DATE) + "-"
                    + range.endExclusive().format(DATE) + "@" + uidSuffix);
            line(out, "END:VEVENT");
        }
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    /**
     * 한 줄을 쓴다. RFC 5545 는 줄 끝이 CRLF 이고 75옥텟에서 접어야 한다.
     *
     * <p>우리 줄은 전부 짧아 실제로 접히지 않지만, 접는 규칙을 여기 한 곳에 두면
     * {@code SUMMARY} 를 길게 바꿔도 발행물이 깨지지 않는다.
     */
    private static void line(StringBuilder out, String content) {
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= 75) {
            out.append(content).append("\r\n");
            return;
        }
        int start = 0;
        boolean first = true;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + (first ? 74 : 73));
            out.append(first ? "" : " ").append(content, start, end).append("\r\n");
            start = end;
            first = false;
        }
    }
}
