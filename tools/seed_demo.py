"""업체 회의 시연용 시드 데이터. 시연-01 문서의 3절 "전날" 목록이다.

일회용이다. 프로덕션 코드가 아니고 아무도 이걸 import 하지 않는다.
P6 23주차 리허설에서 다시 쓸 수 있게 저장소에 남긴다.

## 도메인을 거쳐서 넣는다

**예약과 요금은 전부 REST API 로 넣는다. 예약 행을 SQL 로 직접 만들지 않는다.**
그렇게 하면 재고 원장(`inventory_ledger`)이 함께 움직이지 않고, 캘린더의 잔여 재고와
리포트의 판매된 객실박이 서로 어긋난다. 시연 중에 그게 드러나면 고칠 자리가 없다.

충돌과 인박스는 **시뮬레이터를 거친다.** 충돌 행을 직접 만들어 넣으면 그 행이
가리키는 예약이 실재하지 않아 해소 화면이 깨지고, 무엇보다 대본 3번이 보여 주려는
것이 "채널 예약이 재고를 넘겨 들어오는 경로"라서 그 경로로 만들어야 한다.

## SQL 로 손대는 것은 두 컬럼뿐이다

둘 다 **재고와 무관한 표시용 값**이고, 넣을 API 자체가 없다.

- `reservation.channel_code` — `registerManual` 이 항상 `DIRECT` 로 박는다. 채널을
  가르려면 실제 채널 수신을 태워야 하는데, 시드 열몇 건을 위해 시뮬레이터 시나리오를
  짜는 것은 배보다 배꼽이다. `ReportTest` 도 같은 이유로 같은 방식을 쓴다
- `reservation.created_at` — 리드타임이 `check_in - created_at::date` 라서, 지난
  날짜의 예약을 오늘 만들면 **리드타임이 음수로 뜬다.** 시연 7번이 그 화면이다

**채널 코드는 `frontend/src/calendar/channels.ts` 의 라벨 맵에 있는 값을 쓴다.**
거기 없는 코드를 넣으면 캘린더 막대가 회색으로 그려지고 리포트에 코드가 그대로
뜬다. 처음에 `MOCK_OTA`·`AIRBNB` 로 넣었다가 둘 다 그랬다.

## 쓰는 법

    python tools/seed_demo.py            # 백엔드(8080) + 시뮬레이터(8081) 가 떠 있어야 한다
    python tools/seed_demo.py --dry-run  # 무엇을 넣을지만 출력한다

빈 데이터베이스를 전제한다. 이미 같은 계정이 있으면 가입에서 멈춘다 —
`./gradlew resetLocalDb` 로 지우고 다시 돌린다(백엔드를 먼저 내려야 한다).

필요한 것: `pip install pg8000` (순수 파이썬 드라이버, 컴파일 없음)
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request
from datetime import date, timedelta

# 윈도우 콘솔은 cp949 라 em dash 하나에 UnicodeEncodeError 로 죽는다.
# 출력만 UTF-8 로 돌린다 — 스크립트가 하는 일과 무관한 사고를 막는다.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:8080"
SIM = "http://localhost:8081"
# 시뮬레이터 자신의 키다. mock-ota-simulator/application.yml 의 mockota.api-key.
SIM_API_KEY = "mock-ota-dev-key"

# 내장 PostgreSQL. EmbeddedPostgresConfig 가 postgres/postgres 로 띄운다.
DB = dict(host="localhost", port=15432, database="postgres",
          user="postgres", password="postgres")

# --- 넣을 것 -------------------------------------------------------------------

ORG_NAME = "다온스테이"
LOGIN_EMAIL = "demo@daonstay.kr"
LOGIN_PASSWORD = "다온스테이시연2026!"
DISPLAY_NAME = "김다온"

PROPERTIES = [
    {
        "name": "제주 애월 돌담집",
        "address": "제주특별자치도 제주시 애월읍 애월로 1길 22",
        "units": [
            ("본채 (기준 2인)", "ENTIRE_PLACE", 1, 180000),
            ("별채 (기준 4인)", "ENTIRE_PLACE", 1, 240000),
            ("다락방", "PRIVATE_ROOM", 2, 90000),
        ],
    },
    {
        "name": "강릉 사천 바다뷰",
        "address": "강원특별자치도 강릉시 사천면 진리해변길 40",
        "units": [
            ("오션뷰 A", "ENTIRE_PLACE", 1, 210000),
            ("오션뷰 B", "ENTIRE_PLACE", 1, 210000),
        ],
    },
]

# 채널 믹스가 보이려면 갈라져 있어야 한다(시연-01 3절). **라벨 맵에 있는 코드만 쓴다.**
CHANNELS = ["DIRECT", "BOOKING_COM", "AIRBNB_ICAL", "NAVER"]

GUEST_NAMES = [
    "김서연", "이준호", "박민지", "최우진", "정하늘", "강도윤", "윤서아",
    "임태현", "한지우", "오세훈", "신예린", "배준서", "홍가온", "문채원",
    "송민재", "권나윤", "조은우", "장서윤", "노현우", "심유진",
]

TODAY = date.today()
RATE_FROM = TODAY - timedelta(days=100)
RATE_TO = TODAY + timedelta(days=120)

# --- 비워 둘 자리 ---------------------------------------------------------------
#
# **시연이 쓸 날짜를 예약으로 덮으면 그 대본이 죽는다.** 앞으로의 예약을 흩을 때
# 아래 창을 피한다. 숙소 1(제주)이 대본 2·3·8번을 전부 받으므로 거기만 지킨다.

# 대본 8번. 위젯에서 날짜를 고를 자리 — 세 판매 단위가 다 열려 있어야 한다.
WIDGET_FROM = date(2026, 10, 15)
WIDGET_TO = date(2026, 10, 17)

# 대본 2번. Mock 채널 예약이 들어올 자리 — 첫 판매 단위가 비어 있어야 재고가
# 줄어드는 것이 보인다. 시뮬레이터 기본 체크인이 오늘+7 이라 그 언저리를 비운다.
MOCK_FROM = TODAY + timedelta(days=5)
MOCK_TO = TODAY + timedelta(days=14)

# 대본 3번. 충돌을 만들 자리. 여기는 우리가 일부러 채운다.
CONFLICT_IN = TODAY + timedelta(days=20)
CONFLICT_NIGHTS = 2


def blocked(check_in: date, nights: int) -> bool:
    """시연이 쓸 창과 겹치는가. 겹치면 그 날짜에 예약을 넣지 않는다."""
    check_out = check_in + timedelta(days=nights)
    windows = [
        (WIDGET_FROM, WIDGET_TO + timedelta(days=1)),
        (MOCK_FROM, MOCK_TO),
        (CONFLICT_IN, CONFLICT_IN + timedelta(days=CONFLICT_NIGHTS)),
    ]
    return any(check_in < w_to and w_from < check_out for w_from, w_to in windows)


# --- HTTP ----------------------------------------------------------------------

class Api:
    def __init__(self, base: str):
        self.base = base
        self.token: str | None = None

    def call(self, method: str, path: str, body=None, headers: dict | None = None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req) as res:
                raw = res.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            raise SystemExit(
                f"[실패] {method} {path} → HTTP {e.code}\n{detail}\n"
                f"백엔드(8080)가 떠 있는지, 이미 같은 이메일로 가입돼 있지 않은지 본다.\n"
                f"이미 있으면 ./gradlew resetLocalDb 로 지우고 다시 돌린다."
            ) from e
        except urllib.error.URLError as e:
            raise SystemExit(
                f"[실패] {method} {path} → 서버에 닿지 않는다({e.reason}).\n"
                f"백엔드는 ./gradlew bootRun --args='--spring.profiles.active=local',\n"
                f"시뮬레이터는 ./gradlew runSimulator 로 띄운다."
            ) from e

    def post(self, path, body=None, headers: dict | None = None):
        return self.call("POST", path, body, headers)

    def get(self, path):
        return self.call("GET", path)


def scenario(name: str, body: dict) -> dict:
    """시뮬레이터의 시나리오를 건다.

    **본문 키는 snake_case 다.** 시뮬레이터의 ObjectMapper 가 SNAKE_CASE 라
    camelCase 로 보내면 **조용히 기본값이 쓰인다**(CLAUDE.md 의 함정 목록).
    14주차에 예약번호가 달라져 스레드가 예약에 안 이어진 적이 있다.

    **시나리오 경로에도 API 키가 필요하다.** 악조건 주입은 이 경로를 비껴가지만
    자격 증명 검사는 `/api/**` 전체에 걸린다 — 웹훅을 쏘게 만드는 것이 시나리오
    경로라서 열어 두면 "웹훅 발신에 자격 증명이 필요하다"가 검증되지 않는다.
    """
    return Api(SIM).post(f"/api/scenarios/{name}", body,
                         headers={"X-Api-Key": SIM_API_KEY})


# --- 시드 ----------------------------------------------------------------------

def signup(api: Api) -> None:
    """조직과 계정을 새로 만든다.

    **깨끗한 조직 하나가 요점이다.** 확인용 조직이 섞여 있으면 시연 중에 그 이름이
    보인다.
    """
    body = api.post("/api/auth/signup", {
        "email": LOGIN_EMAIL,
        "password": LOGIN_PASSWORD,
        "displayName": DISPLAY_NAME,
        "orgName": ORG_NAME,
    })
    api.token = body["accessToken"]
    print(f"  조직 '{ORG_NAME}' 과 계정 {LOGIN_EMAIL} 을 만들었다.")


def create_properties(api: Api) -> list[dict]:
    made = []
    for spec in PROPERTIES:
        prop = api.post("/api/properties",
                        {"name": spec["name"], "address": spec["address"]})
        units = []
        for name, kind, total, price in spec["units"]:
            unit = api.post(f"/api/properties/{prop['id']}/units", {
                "name": name,
                "unitKind": kind,
                "totalUnits": total,
                "basePrice": price,
            })
            units.append({"id": unit["id"], "name": name, "base": price,
                          "total": total})
        made.append({"id": prop["id"], "name": spec["name"], "units": units})
        print(f"  숙소 [{prop['id']}] {spec['name']} — 판매 단위 {len(units)}개")
    return made


def fill_rates(api: Api, properties: list[dict]) -> None:
    """시연 기간 전체에 요금을 넣는다.

    **요금이 없으면 위젯 금액이 0 이 되고 리포트 ADR 이 비어 보인다**(시연-01 3절).
    """
    # **판매 단위마다 따로 넣는다.** 한 숙소의 단위들을 한 번에 묶어 같은 값을 넣으면
    # 다락방이 본채와 같은 값에 팔린다. 처음에 그렇게 했다가 캘린더에서 드러났다.
    for prop in properties:
        for unit in prop["units"]:
            base = unit["base"]
            for weekdays, price, min_stay, stop in (
                    ([], base, 1, False),
                    (["FRIDAY", "SATURDAY"], int(base * 1.3), None, None)):
                api.post(f"/api/properties/{prop['id']}/calendar/bulk-edit", {
                    "unitIds": [unit["id"]],
                    "from": RATE_FROM.isoformat(),
                    "to": RATE_TO.isoformat(),
                    "weekdays": weekdays,
                    "priceMode": "FIXED",
                    "price": price,
                    "priceRate": None,
                    "minStay": min_stay,
                    "closedToArrival": None,
                    "stopSell": stop,
                    "dryRun": False,
                })
            print(f"  [{prop['id']}] {prop['name']} / {unit['name']} — "
                  f"평일 {base:,} 주말 {int(base * 1.3):,}")


def make_reservation(api: Api, prop: dict, unit: dict, check_in: date,
                     nights: int, guest: str) -> dict:
    """예약 하나. **API 를 거치므로 재고 원장이 함께 움직인다.**

    `registerManual` 이 곧바로 CONFIRMED 로 만든다 — 수기 예약은 이미 성사된 건이라
    결제를 기다리는 HOLD 단계가 없다. 과거 날짜를 막는 검증은 어디에도 없다
    (`StayPeriod` 는 체크아웃이 체크인보다 뒤인지만 본다).
    """
    check_out = check_in + timedelta(days=nights)
    return api.post("/api/reservations", {
        "propertyId": prop["id"],
        "unitId": unit["id"],
        "checkIn": check_in.isoformat(),
        "checkOut": check_out.isoformat(),
        "totalAmount": unit["base"] * nights,
        "adults": 2,
        "children": 0,
        "guestName": guest,
        "guestPhone": "010-%04d-%04d" % (random.randint(1000, 9999),
                                         random.randint(1000, 9999)),
    })


def seed_reservations(api: Api, properties: list[dict]) -> list[dict]:
    """지난 기간의 확정 예약과 앞으로의 예약.

    **리포트가 0 으로 뜨면 시연 7번이 죽고, 앞이 비면 캘린더 기본 화면이 텅 빈
    채로 열린다**(대본 1번). 뒤로도 앞으로도 채운다.
    """
    rng = random.Random(20260911)
    made = []
    names = list(GUEST_NAMES)
    rng.shuffle(names)
    at = 0

    def next_name() -> str:
        nonlocal at
        at += 1
        return names[(at - 1) % len(names)]

    # --- 지난 기간 -------------------------------------------------------------
    for prop in properties:
        for unit in prop["units"]:
            cursor = TODAY - timedelta(days=rng.randint(85, 95))
            while cursor < TODAY - timedelta(days=3):
                nights = rng.choice([1, 2, 2, 3, 4])
                if cursor + timedelta(days=nights) >= TODAY:
                    break
                res = make_reservation(api, prop, unit, cursor, nights, next_name())
                made.append({
                    "id": res["id"], "check_in": cursor,
                    "channel": rng.choices(CHANNELS, weights=[3, 3, 3, 1])[0],
                    # 예약일은 체크인보다 앞이어야 리드타임이 양수로 나온다.
                    "lead": rng.randint(4, 45),
                })
                cursor += timedelta(days=nights + rng.randint(2, 9))
    past = len(made)
    print(f"  지난 기간 확정 예약 {past}건")

    # --- 앞으로 30일 -----------------------------------------------------------
    #
    # **대본이 쓸 창은 피한다.** 위젯 날짜와 Mock 수신 자리를 예약으로 덮으면
    # 그 대본이 죽는다.
    upcoming = 0
    for prop in properties:
        for unit in prop["units"]:
            cursor = TODAY + timedelta(days=rng.randint(1, 4))
            while cursor < TODAY + timedelta(days=30):
                nights = rng.choice([1, 2, 2, 3])
                # 숙소 1 은 대본 2·3·8번을 전부 받는다. 거기만 창을 지킨다.
                if prop is properties[0] and blocked(cursor, nights):
                    cursor += timedelta(days=1)
                    continue
                res = make_reservation(api, prop, unit, cursor, nights, next_name())
                made.append({
                    "id": res["id"], "check_in": cursor,
                    "channel": rng.choices(CHANNELS, weights=[4, 3, 2, 1])[0],
                    "lead": rng.randint(2, 30),
                })
                upcoming += 1
                cursor += timedelta(days=nights + rng.randint(3, 8))
    print(f"  앞으로 30일 확정 예약 {upcoming}건 "
          f"(위젯 {WIDGET_FROM}~{WIDGET_TO}, Mock {MOCK_FROM}~{MOCK_TO} 는 비워 뒀다)")
    return made


def cancel_some(api: Api, reservations: list[dict], how_many: int = 2) -> None:
    """취소된 예약. **취소율이 0 이면 그 칸이 비어 보인다**(시연-01 3절).

    **최근 것으로 고른다.** 취소율은 숙박 기준이라 체크인 날짜가 리포트 기간 안에
    있어야 분자에 잡힌다. 맨 앞부터 집으면 100일 전 예약이 걸려서, 리포트를 지난
    30~60일로 보는 순간 취소율이 0 으로 뜬다 — 실제로 그렇게 났다.
    """
    window = TODAY - timedelta(days=40)
    past = sorted((r for r in reservations if window <= r["check_in"] < TODAY),
                  key=lambda r: r["check_in"], reverse=True)
    for r in past[:how_many]:
        api.post(f"/api/reservations/{r['id']}/cancel")
        print(f"  예약 {r['id']} 취소 (체크인 {r['check_in']})")


def stage_checkout(api: Api, properties: list[dict]) -> None:
    """대본 6번용. **오늘 체크아웃할 수 있게 체크인까지 해 둔다.**

    체크아웃은 CHECKED_IN 에서만 된다. 미리 만들어 두지 않으면 회의 자리에서 예약을
    만들고 체크인부터 해야 한다.

    **다락방을 쓴다.** 2실짜리라 한 자리를 잡아도 대본 2·3번의 재고에 영향이 없다.
    """
    prop = properties[0]
    unit = prop["units"][2]
    res = make_reservation(api, prop, unit, TODAY - timedelta(days=1), 1, "시연용 게스트")
    api.post(f"/api/reservations/{res['id']}/check-in")
    print(f"  예약 {res['id']} ({unit['name']}) 를 CHECKED_IN 으로 뒀다 "
          f"— 대본 6번에서 체크아웃 한 번이면 청소 태스크가 뜬다")


def connect_channels(api: Api, properties: list[dict]) -> dict:
    """Mock 채널과 에어비앤비 iCal 연결.

    **채널 코드를 라벨 맵에 있는 값으로 둔다.** Mock 시뮬레이터가 부킹닷컴 역할이라
    `BOOKING_COM` 이고, iCal 이 `AIRBNB_ICAL` 이다. 임의의 코드를 쓰면 수신된 예약의
    막대가 캘린더에서 회색으로 그려진다.

    **Mock 연결에는 매핑까지 붙인다.** 대본 2번(시뮬레이터에서 예약 발생 → 캘린더
    반영)이 매핑을 타고 들어온다. 매핑이 없으면 수신된 예약이 어느 판매 단위인지
    몰라 버려진다.
    """
    first = properties[0]
    mock = api.post(f"/api/properties/{first['id']}/channels", {
        "channelCode": "BOOKING_COM",
        "adapterType": "MOCK",
        "displayName": "부킹닷컴 (시뮬레이터)",
        "credentials": {"api_key": "mock-ota-dev-key"},
    })
    rooms = {}
    for i, unit in enumerate(first["units"], start=1):
        room = f"mock-room-{i}"
        api.post(f"/api/channels/{mock['id']}/mappings", {
            "unitId": unit["id"], "externalUnitId": room,
        })
        rooms[unit["id"]] = room
    print(f"  부킹닷컴(Mock) 연결 [{mock['id']}] + 매핑 {len(rooms)}개 → 대본 2·3번이 이걸 탄다")

    ical = api.post(f"/api/properties/{first['id']}/channels", {
        "channelCode": "AIRBNB_ICAL",
        "adapterType": "ICAL",
        "displayName": "에어비앤비",
        "credentials": {
            "ical_url": "https://www.airbnb.com/calendar/ical/demo-listing.ics",
        },
    })
    api.post(f"/api/channels/{ical['id']}/mappings", {
        "unitId": first["units"][0]["id"], "externalUnitId": "airbnb-listing-1",
    })
    print(f"  에어비앤비 iCal 연결 [{ical['id']}] + 매핑 1개")
    return {"mockConnectionId": mock["id"], "rooms": rooms,
            "firstUnitId": first["units"][0]["id"]}


# --- 시뮬레이터를 거치는 것 -------------------------------------------------------

def count_reservations(channel: str | None, frm: date, to: date) -> int:
    """살아 있는 예약 수. **읽기 전용 질의다.**

    `GET /api/reservations` 가 날짜 필터를 받으면 500 이 나서 여기서만 SQL 로 센다.
    세는 것뿐이라 도메인을 거칠 이유가 없다.
    """
    import pg8000.native
    con = pg8000.native.Connection(**DB)
    try:
        sql = """
            SELECT count(*) FROM reservation r
              JOIN property p ON p.id = r.property_id
              JOIN organization o ON o.id = p.org_id
             WHERE o.name = :org
               AND r.status IN ('CONFIRMED','CHECKED_IN','CHECKED_OUT','NO_SHOW')
               AND r.check_in BETWEEN :frm AND :to
        """
        if channel:
            sql += " AND r.channel_code = :ch"
            return con.run(sql, org=ORG_NAME, frm=frm, to=to, ch=channel)[0][0]
        return con.run(sql, org=ORG_NAME, frm=frm, to=to)[0][0]
    finally:
        con.close()


def wait_for(label: str, probe, timeout: float = 60.0) -> bool:
    """폴링이 반영할 때까지 기다린다. 예약은 5초, 메시지는 10초 주기다."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if probe():
            return True
        time.sleep(2)
    print(f"  [경고] {label} 이(가) {timeout:.0f}초 안에 반영되지 않았다.")
    return False


def seed_conflicts(api: Api, channels: dict, properties: list[dict]) -> None:
    """대본 3번. **충돌을 자연스러운 경로로 만든다.**

    충돌 행을 SQL 로 만들어 넣지 않는다 — 그 행이 가리키는 예약이 실재하지 않으면
    해소 화면이 깨진다. 무엇보다 대본 3번이 보여 주려는 것이 <b>채널 예약이 재고를
    넘겨 들어오는 경로</b>라서 그 경로로 만들어야 한다.

    1실짜리 판매 단위에 같은 날짜로 세 건을 순차로 보낸다. 첫 건은 재고를 잡고
    나머지 둘이 넘친다. **넘친 예약을 거절하지 않는다** — 받아들이고
    `overbooking_conflict` 에 남기는 것이 정해 둔 처리다(CLAUDE.md).
    """
    room = channels["rooms"][channels["firstUnitId"]]
    scenario("overbook", {
        "booking_id": "DEMO-OVERBOOK",
        "room_id": room,
        "check_in": CONFLICT_IN.isoformat(),
        "check_out": (CONFLICT_IN + timedelta(days=CONFLICT_NIGHTS)).isoformat(),
        "guest_name": "Booking.com Guest",
        "total_amount": 180000 * CONFLICT_NIGHTS,
        "count": 3,
    })
    print(f"  시뮬레이터에 {CONFLICT_IN} 로 3건을 보냈다 (1실짜리 판매 단위)")

    ok = wait_for("충돌", lambda: len(api.get("/api/conflicts")) > 0)
    if ok:
        conflicts = api.get("/api/conflicts")
        print(f"  충돌 {len(conflicts)}건이 잡혔다 → /conflicts 가 차 있다")


def seed_inbox(api: Api, channels: dict) -> None:
    """대본 5번. 인박스 스레드.

    **예약을 먼저 만들고 그 예약번호로 메시지를 보낸다.** 순서가 반대면 스레드가
    예약에 이어지지 않아 화면에 게스트 이름도 날짜도 안 뜬다.

    첫 건은 `msg-inbox.json` 의 시나리오다 — **같은 메시지를 3통 보내고 우리 쪽
    유일 제약이 1통으로 흡수한다.** 대본 5번의 "같은 메시지 3통이 1통으로 들어온다"가
    그것이다.
    """
    room = channels["rooms"][channels["firstUnitId"]]
    threads = [
        ("DEMO-MSG-1", "체크인 시간을 조금 늦출 수 있을까요?", 3, 40),
        ("DEMO-MSG-2", "주차 공간이 따로 있나요? 차를 가져가려고 합니다.", 1, 44),
        ("DEMO-MSG-3", "수건을 두 장 더 받을 수 있을까요?", 1, 48),
        ("DEMO-MSG-4", "근처에 아침 먹을 만한 곳 추천해 주실 수 있나요?", 1, 52),
    ]

    for booking_id, _, _, offset in threads:
        check_in = TODAY + timedelta(days=offset)
        scenario("duplicate", {
            "booking_id": booking_id,
            "room_id": room,
            "check_in": check_in.isoformat(),
            "check_out": (check_in + timedelta(days=2)).isoformat(),
            "guest_name": "Booking.com Guest",
            "total_amount": 360000,
            "count": 1,
        })
    print(f"  예약 {len(threads)}건을 시뮬레이터에 넣었다 (체크인은 위젯 창 뒤로 뺐다)")

    # **`GET /api/reservations` 를 쓰지 않는다.** `from`/`to` 를 주면 500 이 난다 —
    # `search` 질의의 `(:from is null or ...)` 가 PostgreSQL 에서 형을 못 정한다.
    # 화면이 부르지 않는 경로라 시연에는 영향이 없지만 여기서는 피해 간다.
    wait_for("채널 예약",
             lambda: count_reservations("BOOKING_COM", TODAY + timedelta(days=35),
                                        TODAY + timedelta(days=60)) >= len(threads),
             timeout=45)

    for booking_id, body, count, _ in threads:
        scenario("guest-message", {
            "booking_id": booking_id, "body": body, "count": count,
        })
    print(f"  게스트 메시지를 보냈다 (첫 건은 같은 메시지 {threads[0][2]}통 — 대본 5번)")

    wait_for("인박스 스레드",
             lambda: len(api.get("/api/inbox")) >= len(threads), timeout=60)
    got = api.get("/api/inbox")
    print(f"  인박스 스레드 {len(got)}건")


# --- SQL 보정 (표시용 두 컬럼) ---------------------------------------------------

def fix_display_columns(reservations: list[dict]) -> None:
    """`channel_code` 와 `created_at` 을 맞춘다.

    **재고에 영향을 주지 않는 두 컬럼이다.** 예약 행도 박 행도 원장도 전부 API 가
    이미 만들어 뒀고, 여기서는 넣을 API 가 없는 표시용 값만 고친다.

    채널 수신으로 들어온 예약은 건드리지 않는다 — 그쪽은 이미 제 채널 코드를 달고
    있고 예약 시각도 진짜다.
    """
    try:
        import pg8000.native
    except ImportError:
        print("\n[건너뜀] pg8000 이 없어 channel_code / created_at 을 못 고쳤다.")
        print("         pip install pg8000 뒤 처음부터 다시 돌린다.")
        print("         이대로 두면 채널 믹스가 DIRECT 하나로 뭉치고 "
              "리드타임이 음수로 뜬다.")
        return

    con = pg8000.native.Connection(**DB)
    try:
        for r in reservations:
            con.run("UPDATE reservation SET channel_code = :ch WHERE id = :id",
                    ch=r["channel"], id=r["id"])
            created = r["check_in"] - timedelta(days=r["lead"])
            con.run("UPDATE reservation SET created_at = :ts WHERE id = :id",
                    ts=f"{created.isoformat()} 10:00:00+09", id=r["id"])
        mix = {}
        for r in reservations:
            mix[r["channel"]] = mix.get(r["channel"], 0) + 1
        print(f"  channel_code 보정: {mix}")
        print(f"  created_at 보정: 예약 {len(reservations)}건 (리드타임 2~45일)")
    finally:
        con.close()


def verify(api: Api) -> None:
    """대본이 쓸 것이 실제로 다 있는지 본다. **비면 그 꼭지가 빈 화면이 된다.**"""
    problems = []

    frm = (TODAY - timedelta(days=60)).isoformat()
    to = TODAY.isoformat()
    m = api.get(f"/api/reports?from={frm}&to={to}")
    print(f"\n[7번] 리포트 ({frm} ~ {to})")
    print(f"  점유율 {float(m['occupancyRate']) * 100:.1f}%  ADR {m['adr']:,.0f}  "
          f"RevPAR {m['revPar']:,.0f}")
    print(f"  리드타임 {m['leadTimeDays']}일  취소율 {float(m['cancellationRate']) * 100:.1f}%")
    print(f"  채널 믹스 {[(c['channelCode'], c['reservations']) for c in m['channelMix']]}")
    if m["soldNights"] == 0:
        problems.append("판매된 객실박이 0 이다")
    if float(m["leadTimeDays"]) <= 0:
        problems.append("리드타임이 0 이하다. created_at 보정이 안 됐다")
    if len(m["channelMix"]) < 2:
        problems.append("채널이 하나뿐이다. channel_code 보정이 안 됐다")
    if float(m["cancellationRate"]) == 0:
        problems.append("취소율이 0 이다")
    expected = float(m["adr"]) * float(m["occupancyRate"])
    if abs(expected - float(m["revPar"])) > max(1.0, float(m["revPar"]) * 0.001):
        problems.append(f"RevPAR 항등식이 어긋난다 ({expected:.0f} vs {m['revPar']})")

    # 대본 1번. 오늘부터 30일에 예약 막대가 보여야 한다.
    upcoming = count_reservations(None, TODAY, TODAY + timedelta(days=30))
    print(f"[1번] 앞으로 30일 예약 {upcoming}건")
    if upcoming < 10:
        problems.append(f"앞으로 30일 예약이 {upcoming}건뿐이다. 캘린더가 비어 보인다")

    conflicts = api.get("/api/conflicts")
    print(f"[3번] 충돌 {len(conflicts)}건")
    if not conflicts:
        problems.append("충돌이 없다. /conflicts 가 빈 화면이다")

    threads = api.get("/api/inbox")
    print(f"[5번] 인박스 스레드 {len(threads)}건")
    if len(threads) < 3:
        problems.append(f"인박스 스레드가 {len(threads)}건뿐이다")

    tasks = api.get("/api/ops/tasks")
    print(f"[6번] 청소 태스크 {len(tasks)}건 (체크아웃 전이라 0 이 맞다)")

    # 대본 8번. 위젯 창이 세 판매 단위 다 열려 있어야 한다.
    avail = api.get(f"/api/properties/{api.get('/api/properties')[0]['id']}"
                    f"/calendar?from={WIDGET_FROM}&to={WIDGET_TO}")
    closed = [u["name"] for u in avail["units"]
              if any(d["avail"] <= 0 or d["stopSell"] for d in u["days"])]
    print(f"[8번] 위젯 창 {WIDGET_FROM}~{WIDGET_TO}: "
          f"{len(avail['units']) - len(closed)}/{len(avail['units'])} 판매 단위 열림")
    if closed:
        problems.append(f"위젯 창이 막힌 판매 단위가 있다: {closed}")

    if problems:
        print("\n[확인 필요]")
        for p in problems:
            print(f"  - {p}")
    else:
        print("\n  대본 여덟 꼭지가 쓸 데이터가 다 있다.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true",
                        help="무엇을 넣을지만 출력한다")
    args = parser.parse_args()

    if args.dry_run:
        print(f"조직 {ORG_NAME} / {LOGIN_EMAIL}")
        for spec in PROPERTIES:
            print(f"  {spec['name']} — 판매 단위 {len(spec['units'])}개")
        print(f"요금 {RATE_FROM} ~ {RATE_TO}")
        print(f"채널 {CHANNELS}")
        print(f"비워 둘 창: 위젯 {WIDGET_FROM}~{WIDGET_TO}, Mock {MOCK_FROM}~{MOCK_TO}")
        print(f"충돌 자리: {CONFLICT_IN} ({CONFLICT_NIGHTS}박)")
        return

    api = Api(BASE)
    print("1. 조직과 계정")
    signup(api)
    print("2. 숙소와 판매 단위")
    properties = create_properties(api)
    print("3. 요금")
    fill_rates(api, properties)
    print("4. 예약")
    reservations = seed_reservations(api, properties)
    print("5. 취소")
    cancel_some(api, reservations)
    print("6. 대본 6번용 체크인")
    stage_checkout(api, properties)
    print("7. 채널 연결")
    channels = connect_channels(api, properties)
    print("8. 표시용 컬럼 보정 (SQL)")
    fix_display_columns(reservations)
    print("9. 충돌 (시뮬레이터 → 채널 수신)")
    seed_conflicts(api, channels, properties)
    print("10. 인박스 (시뮬레이터 → 메시지 수신)")
    seed_inbox(api, channels)

    verify(api)

    print("\n" + "=" * 60)
    print(f"로그인   {LOGIN_EMAIL} / {LOGIN_PASSWORD}")
    for p in properties:
        print(f"숙소 ID  {p['id']}  {p['name']}")
    print(f"위젯     http://localhost:5173/widget/{properties[0]['id']}"
          f"  ({WIDGET_FROM} ~ {WIDGET_TO} 로 고른다)")
    print("=" * 60)


if __name__ == "__main__":
    sys.exit(main())
