"""대본 6번 손잡이. 예약 하나를 체크아웃시킨다.

**화면에 체크아웃 버튼이 없다.** 프론트에서 예약에 하는 쓰기는 막대를 끌어 날짜를
바꾸는 `PATCH` 하나뿐이고, 예약 상세 패널도 상태 전이 버튼도 만들지 않았다
(시연-01 4절의 "API 는 있고 화면이 없다"와 같은 자리다). 그래서 대본 6번은
이 명령으로 건다.

체크아웃하면 `RESERVATION_CHECKED_OUT` 이벤트가 나가고 `CheckoutTaskTrigger` 가
청소 태스크를 만든다. 3초 안에 `/ops/tasks` 에 뜬다.

    python tools/demo_checkout.py 73
    python tools/demo_checkout.py            # 체크아웃할 수 있는 예약을 찾아 보여 준다
"""

from __future__ import annotations

import sys

sys.path.insert(0, "tools")
from seed_demo import Api, BASE, LOGIN_EMAIL, LOGIN_PASSWORD, DB  # noqa: E402

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def checked_in() -> list:
    import pg8000.native
    con = pg8000.native.Connection(**DB)
    try:
        return con.run("""
            SELECT r.id, r.confirmation_code, u.name, r.check_in, r.check_out
              FROM reservation r JOIN unit u ON u.id = r.unit_id
              JOIN property p ON p.id = r.property_id
              JOIN organization o ON o.id = p.org_id
             WHERE r.status = 'CHECKED_IN'
             ORDER BY r.id
        """)
    finally:
        con.close()


def main() -> None:
    api = Api(BASE)
    api.token = api.post("/api/auth/login",
                         {"email": LOGIN_EMAIL, "password": LOGIN_PASSWORD})["accessToken"]

    if len(sys.argv) < 2:
        rows = checked_in()
        if not rows:
            print("체크아웃할 수 있는 예약이 없다. CHECKED_IN 인 예약이 있어야 한다.")
            return
        print("체크아웃할 수 있는 예약:")
        for r in rows:
            print(f"  {r[0]}  {r[1]}  {r[2]}  {r[3]} ~ {r[4]}")
        print(f"\n  python tools/demo_checkout.py {rows[0][0]}")
        return

    reservation_id = sys.argv[1]
    before = len(api.get("/api/ops/tasks"))
    result = api.post(f"/api/reservations/{reservation_id}/check-out")
    print(f"예약 {reservation_id} → {result['status']}")

    # 청소 태스크는 Outbox 를 거쳐 만들어진다. 릴레이가 1초 주기라 곧 뜬다.
    import time
    for _ in range(10):
        time.sleep(1)
        tasks = api.get("/api/ops/tasks")
        if len(tasks) > before:
            t = tasks[-1]
            print(f"청소 태스크가 생겼다 — {t.get('unitName')} / {t.get('status')} "
                  f"/ 기한 {str(t.get('dueFrom'))[:16]} ~ {str(t.get('dueTo'))[:16]}")
            print("→ /ops/tasks 를 연다")
            return
    print("[경고] 청소 태스크가 아직 안 보인다. backend.log 를 본다.")


if __name__ == "__main__":
    main()
