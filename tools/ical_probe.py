"""업체 iCal 피드를 한 번씩 받아 모양만 센다. **주소는 절대 찍지 않는다.**

주소는 .env 의 AIRBNB_ICAL_URL_1..N 에서 읽고 순번으로만 부른다(작업지시-15 4절 —
주소를 아는 사람은 누구나 그 숙소의 예약 일정을 읽는다). DESCRIPTION 에는 예약 URL 과
게스트 전화 뒷자리가 있으므로 그것도 찍지 않는다. 세는 것은 개수·날짜 범위·SUMMARY 종류다.

    python tools/ical_probe.py

`Airbnb (Not available)` 의 날짜를 따로 찍는다. 2026-09-15 에 처음 받았을 때 세 피드
모두 DTSTART 로부터 정확히 365일 뒤 하루가 그 이벤트였다 — 호스트 차단이 아니라
에어비앤비 1년 창의 끝 표식일 수 있다. 다음 날 다시 받아 하루 밀리면 표식이다.
"""
from __future__ import annotations

import re
import sys
import urllib.request
from datetime import date
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ENV = Path(__file__).resolve().parent.parent / ".env"


def read_urls() -> list[str]:
    urls = {}
    for line in ENV.read_text(encoding="utf-8").splitlines():
        m = re.match(r"AIRBNB_ICAL_URL_(\d+)=(\S+)", line)
        if m:
            urls[int(m.group(1))] = m.group(2)
    return [urls[k] for k in sorted(urls)]


def unfold(text: str) -> list[str]:
    # RFC 5545 접는 줄. IcalParser 와 같은 규칙이다.
    return re.sub(r"\r?\n[ \t]", "", text).splitlines()


def events_of(body: str) -> list[dict]:
    events, cur = [], None
    for ln in unfold(body):
        if ln == "BEGIN:VEVENT":
            cur = {}
        elif ln == "END:VEVENT" and cur is not None:
            events.append(cur)
            cur = None
        elif cur is not None and ":" in ln:
            k, v = ln.split(":", 1)
            cur[k.split(";")[0]] = v
    return events


def as_date(s: str) -> date:
    return date(int(s[:4]), int(s[4:6]), int(s[6:8]))


def probe(i: int, url: str) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": "StaySync-probe/1"})
    with urllib.request.urlopen(req, timeout=20) as r:
        body = r.read().decode("utf-8", "replace")
        status = r.status
    ev = events_of(body)
    reserved = [e for e in ev if e.get("SUMMARY") == "Reserved"]
    other = [e for e in ev if e.get("SUMMARY") != "Reserved"]
    nights = lambda es: sum((as_date(e["DTEND"]) - as_date(e["DTSTART"])).days for e in es)  # noqa: E731
    starts = sorted(e["DTSTART"] for e in ev)
    ends = sorted(e["DTEND"] for e in ev)
    print(f"[{i}] HTTP {status} · {len(body):,} bytes · VEVENT {len(ev)}개 "
          f"(Reserved {len(reserved)}/{nights(reserved)}박, 그 외 {len(other)}/{nights(other)}박)")
    if ev:
        print(f"    DTSTART {starts[0]} ~ {starts[-1]} / DTEND {ends[0]} ~ {ends[-1]}")
    for e in other:
        print(f"    그 외: SUMMARY={e.get('SUMMARY')!r} {e['DTSTART']} ~ {e['DTEND']}")


if __name__ == "__main__":
    urls = read_urls()
    if not urls:
        sys.exit(".env 에 AIRBNB_ICAL_URL_1.. 이 없다")
    print(f"오늘 {date.today()}")
    for i, u in enumerate(urls, 1):
        try:
            probe(i, u)
        except Exception as e:  # noqa: BLE001 — 순번과 예외 종류만. 메시지에 주소가 들어 있을 수 있다
            print(f"[{i}] 실패: {type(e).__name__}")
