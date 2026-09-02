"""30객실 x 90일 데이터를 넣고 캘린더 API 응답 시간을 잰다.

작업지시 04 의 완료 조건 13. 목표는 8.2 의 초기 렌더링 1초다.
여기서 재는 것은 서버 왕복이고, 브라우저 렌더링은 별도로 잰다.
"""

import json
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
EMAIL = "perf@example.com"
PASSWORD = "충분히긴비밀번호1234"
UNITS = 30
DAYS = 90


def call(path, method="GET", body=None, token=None):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read().decode("utf-8")
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        return e.code, (json.loads(raw) if raw else None)


def main():
    # 가입 또는 로그인
    status, body = call("/api/auth/signup", "POST", {
        "email": EMAIL, "password": PASSWORD,
        "displayName": "성능측정", "orgName": "성능 조직"})
    if status == 409:
        status, body = call("/api/auth/login", "POST", {"email": EMAIL, "password": PASSWORD})
    assert status in (200, 201), (status, body)
    token = body["accessToken"]
    print(f"인증 완료 (status={status})")

    # 숙소 확보
    status, props = call("/api/properties", token=token)
    if props:
        property_id = props[0]["id"]
        print(f"기존 숙소 재사용: id={property_id}")
    else:
        status, prop = call("/api/properties", "POST",
                            {"name": "성능측정 숙소", "address": "서울"}, token)
        assert status == 201, (status, prop)
        property_id = prop["id"]
        print(f"숙소 생성: id={property_id}")

    # 판매 단위 30개
    status, units = call(f"/api/properties/{property_id}/units", token=token)
    existing = len(units or [])
    for i in range(existing, UNITS):
        status, _ = call(f"/api/properties/{property_id}/units", "POST", {
            "name": f"객실 {i + 1:02d}", "unitKind": "PRIVATE_ROOM",
            "totalUnits": 1, "basePrice": 90000}, token)
        assert status == 201, status
    print(f"판매 단위 {UNITS}개 확보 (신규 {UNITS - existing}개)")

    status, units = call(f"/api/properties/{property_id}/units", token=token)
    unit_ids = [u["id"] for u in units]

    # 예약을 흩어 넣어 원장 행을 만든다. 객실당 3건이면 90일 중 상당수가 채워진다.
    from datetime import date, timedelta
    base = date.today()
    made = 0
    for idx, unit_id in enumerate(unit_ids):
        for k in range(3):
            start = base + timedelta(days=(idx * 2 + k * 25) % (DAYS - 4))
            end = start + timedelta(days=3)
            status, _ = call("/api/reservations", "POST", {
                "propertyId": property_id, "unitId": unit_id,
                "checkIn": start.isoformat(), "checkOut": end.isoformat(),
                "totalAmount": 270000, "adults": 2, "children": 0,
                "guestName": f"게스트{idx}-{k}",
                "guestPhone": "010-0000-0000", "guestEmail": "g@example.com"}, token)
            if status == 201:
                made += 1
    print(f"예약 {made}건 생성")

    # 측정
    frm = base.isoformat()
    to = (base + timedelta(days=DAYS - 1)).isoformat()
    path = f"/api/properties/{property_id}/calendar?from={frm}&to={to}"

    # 첫 호출은 JIT 와 커넥션 워밍업이 섞이므로 따로 적는다
    t0 = time.perf_counter()
    status, grid = call(path, token=token)
    cold = (time.perf_counter() - t0) * 1000
    assert status == 200, (status, grid)

    warm = []
    for _ in range(10):
        t0 = time.perf_counter()
        call(path, token=token)
        warm.append((time.perf_counter() - t0) * 1000)
    warm.sort()

    cells = sum(len(u["days"]) for u in grid["units"])
    print()
    print("===== 완료 조건 13 측정 =====")
    print(f"판매 단위 {len(grid['units'])}개 x {DAYS}일 = {cells}셀, 예약 막대 {len(grid['reservations'])}개")
    print(f"응답 크기 {len(json.dumps(grid, ensure_ascii=False).encode('utf-8')) / 1024:.1f} KB")
    print(f"첫 호출(cold)  {cold:.0f} ms")
    print(f"이후 10회 중앙값 {warm[len(warm) // 2]:.0f} ms   최소 {warm[0]:.0f} ms   최대 {warm[-1]:.0f} ms")


if __name__ == "__main__":
    main()
