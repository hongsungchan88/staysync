# 확인 12. 지니하우스 Channex 전환 — 작업지시-21

- 시작: 2026-09-27
- 대상: Channex 스테이징(`staging.channex.io`), 로컬 앱. 배포 환경은 읽기만(4절)
- 규칙: **API 키·iCal 주소·게스트 개인정보를 적지 않는다.** 식별자는 비밀이 아니다. 리허설 손님은 가명 "Te st"

작업지시-21 7절의 기록이다. 1절 원화 요금 실측, 2절 로컬 리허설, 3절 실제 전환(9절 뒤, 비어 있음), 4절 대조.

---

## 1. 원화 요금 전송 형식 (09-27, 스테이징 KRW 개발 숙소)

숙소 `2aacda1a-4f87-4ad4-b31d-5d996079427f`(`StaySync 개발 테스트 KRW`, 통화 KRW)에는 객실 `디럭스 더블`
(`40a98492-…`) 하나만 있고 요금제가 없었다. 측정용 요금제를 하나 만들었다 — **`[측정] 원화 형식 (작업지시-21 D)`
`28d1320b-8ebb-4451-b627-3c69680d3057`**(per_room · manual · 2인 primary). 생성 때 `"rate":"100000.00"` 을 줬더니
`options[0].rate` 가 `"100000"` 으로 돌아왔다. 숙소의 다른 것은 건드리지 않았다(작업지시-21 5절 3번).

`POST /api/v1/restrictions {"values":[{property_id, rate_plan_id, date, rate}]}` 를 날짜마다 따로 보내고
`GET /api/v1/restrictions?filter[property_id]=…&filter[date][gte]=…&filter[restrictions]=rate` 로 되읽었다.

| 날짜 | 보낸 `rate` | 응답 | 되읽은 값 |
|---|---|---|---|
| 2027-03-01 | `"170000.00"` (문자열, 소수 둘째 자리 — **지금 어댑터 형식**) | 200 `Success`, 경고 없음 | **`"170000"`** |
| 2027-03-02 | `170000` (정수) | 200 `Success` | **`"170000"`** |
| 2027-03-03 | `"170000"` (문자열, 소수 없음) | 200 `Success` | **`"170000"`** |
| 2027-03-04 | `"170000.50"` (원 미만) | 200 `Success`, 경고 없음 | `"170001"` — **조용히 반올림** |
| 2027-03-05 | `"99"` | 200 `Success` | `"99"` |

**결론 — 어댑터 형식을 바꾸지 않는다.** 원화는 소수 자리가 없는 통화라 최소 단위가 1원이고, 세 형식 모두 같은
값으로 들어간다. USD 에서 정수 `10000` 이 100.00 으로 읽힌 것(확인-10 1절)은 최소 단위가 1센트라서다. 지금 형식
`"170000.00"` 은 USD·KRW 모두에서 맞는 유일한 형식이므로 통화별 분기가 필요 없다. 원 미만은 반올림되지만 우리 요금은
원 단위 정수다. 되읽기 값에 소수가 없어도 대조 배치는 `compareTo` 로 비교해(`ChannelReconcileJob`) 불일치로 잡지
않는다. 근거는 `ChannexAdapter.money` 의 javadoc 에 옮겼다.

---

## 2. 로컬 리허설 (09-27 오후, 작업지시-21 2절 E)

**스테이징 쪽 — 리허설 전용 숙소를 새로 만들었다.** 지니하우스 실물 전환용 숙소는 9절에서 만든다 — 리허설 예약
기록이 실물 숙소에 남지 않게 갈랐다.

| | 식별자 | 비고 |
|---|---|---|
| 숙소 | `74e79218-2207-41f3-b95d-8f16f4ff97f5` | `[리허설] 지니하우스 전환 (KRW)` · apartment · Asia/Seoul · KR · KRW |
| 객실 유형 | `2af56ce2-9e8a-4e90-b538-a5ffe10e7521` | `리허설 객실` · 1실 · 성인 4·아동 2 |
| 요금제 | `ce34bebe-6135-4b70-b1de-09a9ef550e6f` | `기본 요금 (KRW)` · per_room · manual · `"170000.00"` |
| Booking CRS | 설치 `3ae97ab5-21e1-4699-8f75-87faf719687a` | 확인-10 3.2 와 같은 경로 |

**로컬 쪽.** `bootRun`(local)에 iCal·Channex 폴링을 20초로 줄이는 인자만 더해 띄웠다(`.claude/launch.json` 의
`backend-rehearsal`). 새 조직 `작업지시21 리허설 …`, 숙소 `지니하우스 리허설`, 판매 단위 `지니하우스`(1실, 170,000원).
iCal 발행물은 로컬 정적 파일(`http://127.0.0.1:8099/jinnie.ics`, 에어비앤비 모양 `SUMMARY:Reserved` 셋)로 흉내 냈다.

| 단계 | 한 일 | StaySync | 판정 |
|---|---|---|---|
| 1 | iCal 연결(`AIRBNB_ICAL`) + 매핑 | 예약 셋: 120 `10-10→12`, 121 `10-20→23`, 122 `10-30→11-01`. 이름·금액·**인원 없음**(인원 0 = 모름이라 막대에 안 실린다). 막힌 밤 7 | 정상 |
| 2 | **iCal 매핑 해제** → 30초(iCal 폴링 한 번 이상) 대기 | 예약 셋 그대로, 막힌 밤 7 그대로 | 20 의 확인 문구 "이미 받은 예약은 캘린더에 남습니다" 가 사실이다. `cancelMissing` 이 매핑된 판매 단위에만 돈다 |
| 3 | Channex 연결(`AIRBNB`, `CHANNEX`) + 매핑(객실·요금제) | 201 · 201 | iCal 매핑이 풀려 있어 `MAPPING_DOUBLE_INTAKE` 없음 |
| 4 | Booking CRS `POST /api/v1/bookings` 셋 — `ota_name "Airbnb"`, KRW, 손님 "Te st", 성인 3·아동 1, 박당 `"170000.00"`. R1 `10-10→12`·R2 `10-20→23`(iCal 과 같음), **R3 `10-30→11-02`(하루 어긋남)** | Channex: 셋 다 200 `new`, `unique_id` `ABB-JINNIE-R1~3` | — |
| 5 | 다음 폴링 | **120·121 이 넘겨받혔다** — id 그대로, 채널 `AIRBNB`, "Te st", 340,000원·510,000원, 성인 3·아동 1. **R3 은 새 예약 123** + 충돌 카드 둘(`10-30`·`10-31`, 예약 122·123). 막힌 밤 8(7 + 11-01). 리포트 채널 비중 **한 줄** `AIRBNB` 4건(미상 1 — 남은 iCal 122 — 이라 매출은 계산 안 함) | 완료 조건 1·2·6 |
| 6a | CRS `PUT` R1 `modified` `10-10→13` | 120 이 `10-13`·510,000원으로 따라갔다 | 완료 조건 4 |
| 6b | CRS `PUT` R2 `cancelled` | 121 `CANCELLED`, 막힌 밤 6. **Channex 재고 10-20~22 = 1 로 돌아왔다**(ADR 0014 되보내기) | 완료 조건 4 |
| 정리 | CRS `PUT` R1·R3 `cancelled` | 120·123 `CANCELLED`, 충돌 카드 둘 `AUTO_CLOSED`(열린 것 0), 남은 것은 iCal 122 하나 | 실물 예약은 만든 자리에서 끝냈다(확인-10 3.1) |

**금액.** KRW CRS 예약의 리비전 `amount` 를 어댑터가 그대로 읽어 340000.00·510000.00 으로 저장했다. 통화 검사(숙소
통화 KRW = 예약 통화 KRW)를 통과했다.

**리허설에서 보인 것 — 새 Channex 객실의 재고는 0 에서 시작한다.** 되읽기에서 우리가 보낸 적 없는 날은 전부 0 이었다
(10-20~22 만 1 — 취소 되보내기가 보낸 날). 리허설에서는 취소 되보내기만 돌았기 때문이다. 실물 전환은 9절 3번 "재고·요금을
먼저 내보낸다"가 이 구멍을 막는다 — **빠뜨리면 연결하는 순간 Channex 가 에어비앤비 달력을 전부 닫는다.**

---

## 3. 실제 전환 (작업지시-21 9절 — 비어 있음)

호스트 승인 뒤 사람과 함께 한다. 그때 채운다.

---

## 4. 대조

### 4.1 전환 뒤 남은 앞으로의 iCal 예약 — 0 이어야 한다

```sql
-- 판매 단위마다, 앞으로 남은 살아 있는 iCal 예약(그 숙소 iCal 연결의 채널 코드로 들어온 것).
SELECT u.name AS unit, count(r.id) AS remaining_ical,
       min(r.check_in) AS first_check_in, max(r.check_out) AS last_check_out
FROM unit u
JOIN property p ON p.id = u.property_id
LEFT JOIN reservation r
       ON r.unit_id = u.id
      AND r.status IN ('HOLD', 'CONFIRMED', 'CHECKED_IN')
      AND r.check_out > (now() AT TIME ZONE 'Asia/Seoul')::date
      AND r.channel_code IN (SELECT c.channel_code FROM channel_connection c
                             WHERE c.property_id = r.property_id AND c.adapter_type = 'ICAL')
GROUP BY u.id, u.name
ORDER BY u.id;
```

서버에서 읽기 전용으로 돌린다(09-27 에 실제로 쓴 모양 — 위 쿼리를 `f21.sql` 로 저장해 둔다):

```bash
scp -q f21.sql staysync:/tmp/f21.sql
ssh staysync 'cd /opt/staysync && docker compose exec -T postgres psql -U staysync -d staysync -v ON_ERROR_STOP=1 -c "SET default_transaction_read_only = on;" -f - < /tmp/f21.sql; rm -f /tmp/f21.sql'
```

**전환 전 기준값(09-27, 배포 환경, 읽기만)** — 지니하우스 **12**(첫 체크인 09-25 ~ 마지막 체크아웃 11-30), 메종드서촌
2층 22, 3층 16, 시험 객실 0. 첫 체크인이 09-25 라 **지금 묵고 있는 손님이 있을 수 있다** — 넘겨받기에 CHECKED_IN 을
넣은 이유다(작업지시-21 8절).

**쿼리가 iCal 연결에 기대는 이유.** 넘겨받기도 같은 규칙이다(같은 숙소 iCal 연결들의 채널 코드). **전환 때 iCal
연결은 지우지 않고 매핑만 푼다** — 연결을 지우면 넘겨받기가 코드를 몰라 새 예약 + 충돌이 되고, 이 쿼리도 0 을 거짓으로
보인다.

**리허설에서 쿼리가 할 일을 보였다.** 정리 뒤 남은 iCal 122 가 바로 "Channex 가 같은 날짜로 넘겨주지 않은 예약"의
모양이다(R3 이 하루 어긋나 새 예약이 됐다). 실물에서 이 수가 0 이 아니면 그 예약은 앞으로 변경·취소를 받을 길이
없다 — 에어비앤비 호스트 화면과 날짜를 맞춰 보고, 같은 예약이면 충돌 해소로 정리한다.

### 4.2 기존 예약이 안 넘어올 때 — `load_future_reservations`

스테이징 `GET /api/v1/channels/adapter?code=AirBNB` → 200, `actions: ["load_future_reservations"]` — **에어비앤비
어댑터가 이 동작을 선언한다**(09-27, 읽기만). 부킹닷컴 채널 응답에 있던 것과 같다(확인-10 1.1). **Channex 문서에는
연결 직후 기존 예약을 넘겨주는지도, 이 동작을 부르는 API 도 없다**(ARI·Airbnb 매핑 가이드·전체 텍스트 확인). 부르는
경로를 추측해 스테이징에서 쏘지 않았다.

**9절 6번의 절차:**

1. 호스트가 연결을 승인하고 매핑이 켜지면 **첫 Channex 폴링(60초)을 두 번 기다린다**
2. 4.1 쿼리를 돌린다. 0 이면 끝
3. 0 이 아니면 `load_future_reservations` 를 실행한다. **부르는 방법은 아직 모른다** — Channex 콘솔의 채널 화면에
   그 동작이 보이는지를 그때 먼저 보고, 없으면 Channex 지원에 경로를 묻는다. 확인한 방법을 이 절에 적는다
4. 폴링 두 번 뒤 4.1 을 다시 돌린다. 남은 것은 4.1 의 마지막 문단대로 사람이 맞춘다
