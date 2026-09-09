"""업체 회의 시연용 시드 데이터. 시연-01 문서의 3절 "전날" 목록이다.

일회용이다. 프로덕션 코드가 아니고 아무도 이걸 import 하지 않는다.
P6 23주차 리허설에서 다시 쓸 수 있게 저장소에 남긴다.

## 도메인을 거쳐서 넣는다

**예약과 요금은 전부 REST API 로 넣는다. 예약 행을 SQL 로 직접 만들지 않는다.**
그렇게 하면 재고 원장(`inventory_ledger`)이 함께 움직이지 않고, 캘린더의 잔여 재고와
리포트의 판매된 객실박이 서로 어긋난다. 시연 중에 그게 드러나면 고칠 자리가 없다.

## SQL 로 손대는 것은 두 컬럼뿐이다

둘 다 **재고와 무관한 표시용 값**이고, 넣을 API 자체가 없다.

- `reservation.channel_code` — `registerManual` 이 항상 `DIRECT` 로 박는다. 채널을
  가르려면 실제 채널 수신을 태워야 하는데, 시드 열몇 건을 위해 시뮬레이터 시나리오를
  짜는 것은 배보다 배꼽이다. `ReportTest` 도 같은 이유로 같은 방식을 쓴다
- `reservation.created_at` — 리드타임이 `check_in - created_at::date` 라서, 지난
  날짜의 예약을 오늘 만들면 **리드타임이 음수로 뜬다.** 시연 7번이 그 화면이다

## 쓰는 법

    python tools/seed_demo.py            # 백엔드(8080)가 떠 있어야 한다
    python tools/seed_demo.py --dry-run  # 무엇을 넣을지만 출력한다

필요한 것: `pip install pg8000` (순수 파이썬 드라이버, 컴파일 없음)
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import urllib.error
import urllib.request
from datetime import date, timedelta

# 윈도우 콘솔은 cp949 라 em dash 하나에 UnicodeEncodeError 로 죽는다.
# 출력만 UTF-8 로 돌린다 — 스크립트가 하는 일과 무관한 사고를 막는다.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://localhost:8080"

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

# 채널 믹스가 보이려면 갈라져 있어야 한다. 시연-01 3절.
CHANNELS = ["DIRECT", "MOCK_OTA", "AIRBNB"]

GUEST_NAMES = [
    "김서연", "이준호", "박민지", "최우진", "정하늘", "강도윤", "윤서아",
    "임태현", "한지우", "오세훈", "신예린", "배준서", "홍가온", "문채원",
    "송민재", "권나윤",
]

TODAY = date.today()
# 요금을 넣을 창. 지난 예약이 들어갈 만큼 뒤로, 위젯에서 잡을 만큼 앞으로.
RATE_FROM = TODAY - timedelta(days=100)
RATE_TO = TODAY + timedelta(days=120)


# --- HTTP ----------------------------------------------------------------------

class Api:
    def __init__(self, base: str):
        self.base = base
        self.token: str | None = None

    def call(self, method: str, path: str, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req) as res:
                raw = res.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            raise SystemExit(
                f"[실패] {method} {path} → HTTP {e.code}\n{detail}\n"
                f"백엔드(8080)가 떠 있는지, 이미 같은 이메일로 가입돼 있지 않은지 본다."
            ) from e
        except urllib.error.URLError as e:
            raise SystemExit(
                f"[실패] {method} {path} → 백엔드에 닿지 않는다({e.reason}).\n"
                f"./gradlew bootRun --args='--spring.profiles.active=local' 로 먼저 띄운다."
            ) from e

    def post(self, path, body=None):
        return self.call("POST", path, body)

    def get(self, path):
        return self.call("GET", path)


# --- 시드 ----------------------------------------------------------------------

def signup(api: Api) -> None:
    """조직과 계정을 새로 만든다.

    **깨끗한 조직을 새로 만드는 것이 요점이다.** `.localdb` 에 `Week15 Check Org`
    같은 확인용 조직이 섞여 있고, 시연 중에 그 이름이 보이면 안 된다.
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
            units.append({"id": unit["id"], "name": name, "base": price})
        made.append({"id": prop["id"], "name": spec["name"], "units": units})
        print(f"  숙소 [{prop['id']}] {spec['name']} — 판매 단위 {len(units)}개")
    return made


def fill_rates(api: Api, properties: list[dict]) -> None:
    """시연 기간 전체에 요금을 넣는다.

    **요금이 없으면 위젯 금액이 0 이 되고 리포트 ADR 이 비어 보인다**(시연-01 3절).
    주말을 더 비싸게 넣어 캘린더가 밋밋하지 않게 한다.
    """
    # **판매 단위마다 따로 넣는다.** 한 숙소의 단위들을 한 번에 묶어 같은 값을 넣으면
    # 다락방이 본채와 같은 값에 팔린다. 처음에 그렇게 했다가 캘린더에서 드러났다.
    for prop in properties:
      for unit in prop["units"]:
        unit_ids = [unit["id"]]
        base = unit["base"]

        # 평일 기본가. weekdays 가 비면 전체 요일이다.
        api.post(f"/api/properties/{prop['id']}/calendar/bulk-edit", {
            "unitIds": unit_ids,
            "from": RATE_FROM.isoformat(),
            "to": RATE_TO.isoformat(),
            "weekdays": [],
            "priceMode": "FIXED",
            "price": base,
            "priceRate": None,
            "minStay": 1,
            "closedToArrival": None,
            "stopSell": False,
            "dryRun": False,
        })
        # 금·토는 30% 올린다. 실제 숙소의 요금표가 이렇게 생겼다.
        api.post(f"/api/properties/{prop['id']}/calendar/bulk-edit", {
            "unitIds": unit_ids,
            "from": RATE_FROM.isoformat(),
            "to": RATE_TO.isoformat(),
            "weekdays": ["FRIDAY", "SATURDAY"],
            "priceMode": "FIXED",
            "price": int(base * 1.3),
            "priceRate": None,
            "minStay": None,
            "closedToArrival": None,
            "stopSell": None,
            "dryRun": False,
        })
        print(f"  [{prop['id']}] {prop['name']} / {unit['name']} — "
              f"{base:,}원 (주말 +30%)")


def make_reservation(api: Api, prop: dict, unit: dict, check_in: date,
                     nights: int, guest: str) -> dict:
    """예약 하나. **API 를 거치므로 재고 원장이 함께 움직인다.**

    `registerManual` 이 곧바로 CONFIRMED 로 만든다 — 수기 예약은 이미 성사된 건이라
    결제를 기다리는 HOLD 단계가 없다. 과거 날짜를 막는 검증은 어디에도 없다
    (`StayPeriod` 는 체크아웃이 체크인보다 뒤인지만 본다).
    """
    check_out = check_in + timedelta(days=nights)
    amount = unit["base"] * nights
    return api.post("/api/reservations", {
        "propertyId": prop["id"],
        "unitId": unit["id"],
        "checkIn": check_in.isoformat(),
        "checkOut": check_out.isoformat(),
        "totalAmount": amount,
        "adults": 2,
        "children": 0,
        "guestName": guest,
        "guestPhone": "010-%04d-%04d" % (random.randint(1000, 9999),
                                         random.randint(1000, 9999)),
    })


def seed_reservations(api: Api, properties: list[dict]) -> list[dict]:
    """지난 기간의 확정 예약과 취소 한둘, 그리고 오늘 체크아웃할 것 하나.

    **리포트가 0 으로 뜨면 시연 7번이 죽는다.** 그래서 지난달과 이번 달에 걸쳐
    넉넉히 넣고 채널을 갈라 둔다.
    """
    rng = random.Random(20260910)
    made = []
    names = list(GUEST_NAMES)
    rng.shuffle(names)
    name_at = 0

    # 지난 100일에 흩어 놓는다. 같은 판매 단위가 겹치지 않게 단위마다 날짜를 민다.
    for prop in properties:
        for unit in prop["units"]:
            cursor = TODAY - timedelta(days=rng.randint(85, 95))
            while cursor < TODAY - timedelta(days=3):
                nights = rng.choice([1, 2, 2, 3, 4])
                if cursor + timedelta(days=nights) >= TODAY:
                    break
                guest = names[name_at % len(names)]
                name_at += 1
                res = make_reservation(api, prop, unit, cursor, nights, guest)
                made.append({
                    "id": res["id"],
                    "check_in": cursor,
                    "channel": rng.choices(CHANNELS, weights=[3, 4, 3])[0],
                    # 예약일은 체크인보다 앞이어야 리드타임이 양수로 나온다.
                    "lead": rng.randint(4, 45),
                })
                # 다음 예약까지 며칠 비운다. 캘린더가 빈틈없이 차 있으면 부자연스럽다.
                cursor += timedelta(days=nights + rng.randint(2, 9))

    print(f"  지난 기간 확정 예약 {len(made)}건")

    # 앞으로의 예약도 조금. 캘린더(시연 1번)가 비어 보이지 않게 한다.
    upcoming = 0
    for prop in properties:
        for unit in prop["units"][:2]:
            start = TODAY + timedelta(days=rng.randint(3, 25))
            nights = rng.choice([2, 3])
            res = make_reservation(api, prop, unit, start, nights,
                                   names[name_at % len(names)])
            name_at += 1
            made.append({"id": res["id"], "check_in": start,
                         "channel": rng.choice(CHANNELS),
                         "lead": rng.randint(2, 20)})
            upcoming += 1
    print(f"  앞으로의 예약 {upcoming}건")
    return made


def cancel_some(api: Api, reservations: list[dict], how_many: int = 2) -> None:
    """취소된 예약. **취소율이 0 이면 그 칸이 비어 보인다**(시연-01 3절).

    지난 기간 것을 고른다 — 취소율은 숙박 기준이라 체크인 날짜가 리포트 기간 안에
    있어야 분자에 잡힌다.
    """
    # **최근 것으로 고른다.** 취소율은 숙박 기준이라 체크인 날짜가 리포트 기간 안에
    # 있어야 분자에 잡힌다. 맨 앞부터 집으면 100일 전 예약이 걸려서, 리포트를 지난
    # 30~60일로 보는 순간 취소율이 0 으로 뜬다 — 실제로 그렇게 났다.
    window = TODAY - timedelta(days=40)
    past = sorted((r for r in reservations if window <= r["check_in"] < TODAY),
                  key=lambda r: r["check_in"], reverse=True)
    for r in past[:how_many]:
        api.post(f"/api/reservations/{r['id']}/cancel")
        print(f"  예약 {r['id']} 취소 (체크인 {r['check_in']})")


def stage_checkout(api: Api, properties: list[dict]) -> dict | None:
    """시연 6번용. **오늘 체크아웃할 수 있게 체크인까지 해 둔다.**

    대본 6번이 "체크아웃 → 청소 태스크"인데, 체크아웃은 CHECKED_IN 에서만 된다.
    미리 만들어 두지 않으면 회의 자리에서 예약 만들고 체크인부터 해야 한다.
    """
    prop = properties[0]
    unit = prop["units"][0]
    check_in = TODAY - timedelta(days=1)
    res = make_reservation(api, prop, unit, check_in, 1, "시연용 게스트")
    api.post(f"/api/reservations/{res['id']}/check-in")
    print(f"  예약 {res['id']} 를 CHECKED_IN 으로 뒀다 "
          f"— 대본 6번에서 체크아웃 한 번이면 청소 태스크가 뜬다")
    return res


def connect_channels(api: Api, properties: list[dict]) -> None:
    """Mock 채널과 에어비앤비 iCal 연결.

    **Mock 연결에는 매핑까지 붙인다.** 대본 2번(시뮬레이터에서 예약 발생 →
    캘린더 반영)이 매핑을 타고 들어온다. 매핑이 없으면 수신된 예약이 어느 판매
    단위인지 몰라 버려진다.

    **에어비앤비 iCal 은 연결만 만든다.** 읽어 갈 주소가 실제 리스팅이어야 하는데
    우리 테스트 리스팅은 미게시라 전 기간이 차단으로 나온다(시연-01 5절). 화면에
    연결이 보이는 것까지가 이번 시연의 범위다.
    """
    first = properties[0]
    mock = api.post(f"/api/properties/{first['id']}/channels", {
        "channelCode": "MOCK_OTA",
        "adapterType": "MOCK",
        "displayName": "부킹닷컴 (시뮬레이터)",
        "credentials": {"api_key": "mock-ota-dev-key"},
    })
    for i, unit in enumerate(first["units"], start=1):
        api.post(f"/api/channels/{mock['id']}/mappings", {
            "unitId": unit["id"],
            "externalUnitId": f"mock-room-{i}",
        })
    print(f"  Mock 채널 연결 [{mock['id']}] + 매핑 {len(first['units'])}개 "
          f"→ 대본 2번이 이걸 탄다")

    ical = api.post(f"/api/properties/{first['id']}/channels", {
        "channelCode": "AIRBNB",
        "adapterType": "ICAL",
        "displayName": "에어비앤비",
        "credentials": {
            "ical_url": "https://www.airbnb.com/calendar/ical/demo-listing.ics",
        },
    })
    api.post(f"/api/channels/{ical['id']}/mappings", {
        "unitId": first["units"][0]["id"],
        "externalUnitId": "airbnb-listing-1",
    })
    print(f"  에어비앤비 iCal 연결 [{ical['id']}] + 매핑 1개")


# --- SQL 보정 (표시용 두 컬럼) ---------------------------------------------------

def fix_display_columns(reservations: list[dict]) -> None:
    """`channel_code` 와 `created_at` 을 맞춘다.

    **재고에 영향을 주지 않는 두 컬럼이다.** 예약 행도 박 행도 원장도 전부 API 가
    이미 만들어 뒀고, 여기서는 넣을 API 가 없는 표시용 값만 고친다.
    """
    try:
        import pg8000.native
    except ImportError:
        print("\n[건너뜀] pg8000 이 없어 channel_code / created_at 을 못 고쳤다.")
        print("         pip install pg8000 뒤 이 스크립트를 처음부터 다시 돌린다.")
        print("         이대로 두면 채널 믹스가 DIRECT 하나로 뭉치고 "
              "리드타임이 음수로 뜬다.")
        return

    con = pg8000.native.Connection(**DB)
    try:
        for r in reservations:
            con.run("UPDATE reservation SET channel_code = :ch WHERE id = :id",
                    ch=r["channel"], id=r["id"])
            created = r["check_in"] - timedelta(days=r["lead"])
            con.run(
                "UPDATE reservation SET created_at = :ts WHERE id = :id",
                ts=f"{created.isoformat()} 10:00:00+09", id=r["id"])
        mix = {}
        for r in reservations:
            mix[r["channel"]] = mix.get(r["channel"], 0) + 1
        print(f"  channel_code 보정: {mix}")
        print(f"  created_at 보정: 예약 {len(reservations)}건 "
              f"(리드타임 4~45일로 벌려 둠)")
    finally:
        con.close()


def verify(api: Api, properties: list[dict]) -> None:
    """리포트가 실제로 숫자를 내는지 본다. **0 으로 뜨면 시연 7번이 죽는다.**"""
    frm = (TODAY - timedelta(days=60)).isoformat()
    to = TODAY.isoformat()
    m = api.get(f"/api/reports?from={frm}&to={to}")
    print(f"\n리포트 확인 ({frm} ~ {to})")
    print(f"  판매된 객실박 {m['soldNights']} / 판매 가능 {m['availableNights']}")
    print(f"  점유율 {m['occupancyRate']:.4f}  ADR {m['adr']:,.0f}  "
          f"RevPAR {m['revPar']:,.0f}")
    print(f"  리드타임 {m['leadTimeDays']}일   취소율 {m['cancellationRate']:.4f}")
    print(f"  채널 믹스 {[(c['channelCode'], c['reservations']) for c in m['channelMix']]}")

    problems = []
    if m["soldNights"] == 0:
        problems.append("판매된 객실박이 0 이다. 리포트 화면이 비어 보인다")
    if float(m["leadTimeDays"]) <= 0:
        problems.append("리드타임이 0 이하다. created_at 보정이 안 됐다")
    if len(m["channelMix"]) < 2:
        problems.append("채널이 하나뿐이다. channel_code 보정이 안 됐다")
    if float(m["cancellationRate"]) == 0:
        problems.append("취소율이 0 이다. 그 칸이 비어 보인다")

    # RevPAR = ADR × 점유율. 화면에서 누구든 곱해 본다.
    expected = float(m["adr"]) * float(m["occupancyRate"])
    if abs(expected - float(m["revPar"])) > max(1.0, float(m["revPar"]) * 0.001):
        problems.append(f"RevPAR 항등식이 어긋난다 ({expected:.0f} vs {m['revPar']})")

    if problems:
        print("\n[확인 필요]")
        for p in problems:
            print(f"  - {p}")
    else:
        print("\n  지표 여섯이 전부 채워졌다.")


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
    connect_channels(api, properties)
    print("8. 표시용 컬럼 보정 (SQL)")
    fix_display_columns(reservations)

    verify(api, properties)

    print("\n" + "=" * 60)
    print(f"로그인   {LOGIN_EMAIL} / {LOGIN_PASSWORD}")
    for p in properties:
        print(f"숙소 ID  {p['id']}  {p['name']}")
    print(f"위젯     {BASE.replace('8080', '5173')}/widget/{properties[0]['id']}")
    print("=" * 60)


if __name__ == "__main__":
    sys.exit(main())
