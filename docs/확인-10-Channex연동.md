# 확인 10. Channex 연동 — 작업지시-17

- 시작: 2026-09-21 (32차)
- 대상: Channex 스테이징(`staging.channex.io`) · 부킹닷컴 테스트 숙소
- 규칙: **API 키는 어디에도 적지 않는다.** 식별자는 비밀이 아니다. 응답을 옮길 때 키·개인정보는 지운다

작업지시-17 7절 "반드시 남길 것"의 기록이다. 절 번호는 브랜치 순서를 따른다 — 1절 콘솔·연결(브랜치 1),
2절 ARI 전송(브랜치 2), 3절 예약 피드(브랜치 3), 4절 문서와 실제가 다른 곳, 5절 시간·레이트 리밋.

---

## 1. Channex 쪽에 있는 것 (09-21 기준)

전부 API 로 만들었다(제품 코드에 생성 기능은 없다 — 5절 3번). 파이썬 `urllib` + `user-api-key` 헤더.

| | 식별자 | 비고 |
|---|---|---|
| 그룹 | `e9057173-ce25-472f-b10b-d87f6733438d` | `User Group`. 채널 생성에 `group_id` 가 필요하다 |
| **시험 숙소 (USD)** | **`17e754e7-9aa8-456a-ad0a-94e1d54bc8f3`** | `StaySync 시험 (USD)` · apartment · Asia/Seoul · KR. **이번 주차가 쓰는 숙소** |
| 객실 유형 | **`92f88770-4d38-4ff8-839d-542672d92c3e`** | `시험 객실` · 1실 · 성인 2 |
| 요금제 | **`46b69549-6f8b-4a55-b594-979850f45379`** | `기본 요금 (USD)` · per_room · manual · 2인 primary · `100.00`(정수 `10000` 으로 만들었더니 최소 단위로 읽혔다) |
| **채널** | **`d9283bf0-c1a5-407e-b37f-bb3fe4fefab7`** | `Booking.com 테스트 11140466` · `BookingCom` · hotel_id `11140466` · **활성** · machine_account `staging-b` · 통화 USD |
| 매핑 | 부킹닷컴 방 `1114046603 Studio with Balcony` → 시험 객실, 요금 `40564345 standard rate` → 기본 요금, occupancy 2, **pricing_type Standard**, primary_occ | 다른 방 `1114046602 Five-Bedroom House` 는 매핑 안 함 |
| 시험 숙소 (EUR) | `09ada1f7-0bba-400e-8650-7174911811e6` | 09-17 에 Cowork 이 만든 것. 객실 `3700d5a2-…`, 요금제 `32b828dc-…`. **채널이 지워져 못 쓴다**(4372137 을 남이 쓴다). 그대로 둔다 |
| 시험 숙소 (GBP) | `f7ec61a3-d4f0-43b4-a81b-8eefdddd8e0a` | 09-21 에 만들었다가 안 쓰게 된 것(11140466 이 GBP 가 아니라 USD 였다). 객실 `49c89c5a-…`, 요금제 `21ffed6c-…`. 그대로 둔다 |
| 개발 테스트 숙소 (KRW) | `2aacda1a-4f87-4ad4-b31d-5d996079427f` | 원래 있던 것. 건드리지 않는다 |

**부킹닷컴 테스트 예약 주소** — `https://secure.booking.com/book.html?hotel_id=11140466&test=1`. 카드는 Channex 문서의
테스트 카드(여기 적지 않는다). 재고가 0 이면 예약을 만들 수 없다 → 브랜치 2 가 먼저다.

### 1.1 연결·매핑에 쓴 요청과 응답 모양

**어댑터 서술자** `GET /api/v1/channels/adapter?code=BookingCom` → 200. 연결 설정(`params`)은 `hotel_id`(string) 하나가
보이는 값이고 `machine_account`(hidden, Channex 가 채움)·`send_email_notifications`·`email`·VCC 넷(hidden). 매핑 설정
(`rate_params`)은 `rate_plan_code`·`room_type_code`·`occupancy`(integer)·`pricing_type`(`Standard`|`OBP`, 기본값 오타
`Standart`)·`primary_occ`·`readonly`. `mapping_mode: room_rate_multioccupancy`, `property_mapping: single`,
`channel_restrictions: {currency: EUR, min_price: 500}`. 코드가 `booking_com` 이면 400, `BookingCom` 이어야 한다.

**채널 쪽 방·요금 읽기** `POST /api/v1/channels/mapping_details {"channel":"BookingCom","settings":{"hotel_id":"…"}}` → 200
```json
{"data":{"rooms":[{"id":1114046603,"title":"Studio with Balcony","max_children":null,
  "rates":[{"id":40564345,"title":"standard rate","readonly":false,"occupancies":[],
            "price_1":false,"pricing":"Standard","max_persons":2,"parent_rate_id":""}]}],
  "pricing_type":"Standard"}}
```
OBP 숙소(4372137·5868189)는 `occupancies: [1,2]`, `pricing: "OBP"` 로 온다.

**채널 생성** `POST /api/v1/channels` → 201 (422 면 `{"errors":{"details":{"settings":["channel with the same settings already exists"]}}}` — 남이 쓰는 숙소)
```json
{"channel":{"channel":"BookingCom","group_id":"<group>","title":"Booking.com 테스트 11140466",
  "properties":["<property>"],"settings":{"hotel_id":"11140466"},
  "rate_plans":[{"rate_plan_id":"<rate_plan>","settings":{"room_type_code":"1114046603","rate_plan_code":"40564345",
     "occupancy":2,"pricing_type":"Standard","primary_occ":true,"readonly":false}}]}}
```
응답 `attributes` 에 `is_active: false`, `currency`(채널 쪽 통화), `settings.mappingSettings.rooms {"1114046603": "<room_type>"}`,
`rate_plans[].id`(매핑 행 식별자), `actions: ["load_future_reservations"]`, `expected_removal_date: null`.

**숙소·매핑 교체** `PUT /api/v1/channels/:id {"channel":{"properties":[…],"rate_plans":[{"rate_plan_id":"<old>","settings":null},{…new…}]}}` → 200.
`settings.mappingSettings.rooms` 는 따라 바뀌지 않아 `{"channel":{"settings":{"hotel_id":"…","mappingSettings":{"rooms":{…}}}}}` 로 한 번 더 PUT 했다.

**준비 확인·활성화** `POST /api/v1/channels/:id/check_readiness` → `{"data":[],"meta":{"message":"Success"}}`(빈 목록 = 막는 것 없음),
`POST /api/v1/channels/:id/activate` → `{"meta":{"message":"Success"}}`. 그 뒤 `is_active: true`.

**연결 확인** `POST /api/v1/channels/test_connection` → `{"data":{"success":true,"errors":null}}`,
`POST /api/v1/channels/connection_details` → `attributes.currency` 와 연결 종류별 `XML Active`. **둘 다 남이 쓰는 숙소에도 200 이다** —
점유 여부는 만들어 봐야 안다.

### 1.2 StaySync 쪽 (브랜치 1, `feat/channex-connection`)

- 연결: 어댑터 유형 `CHANNEX`, **채널 코드 `BOOKING_COM`**, 자격 증명 `api_key`(비밀) + `property_id`(Channex 숙소 UUID). 둘 중 하나가
  비면 400 `CHANNEL_FIELD_MISSING`. 응답·화면에서는 둘 다 `••••` 로 가려진다
- 매핑: `externalUnitId` = Channex `room_type_id`, `externalRateId` = `rate_plan_id`(Channex 는 필수, 없으면 400)
- 같은 판매 단위에 iCal 과 Channex 를 함께 매핑하면 어느 순서든 409 `MAPPING_DOUBLE_INTAKE`
- 능력 선언: 브랜치 1 에서는 없음. `WEBHOOK_BOOKING` 은 끝까지 없다

## 2. 재고·요금 전송 (브랜치 2)

(브랜치 2 에서 채운다)

## 3. 예약 피드 (브랜치 3)

(브랜치 3 에서 채운다)

## 4. Channex 문서와 실제가 다른 곳

| 문서 | 실제 (09-21) |
|---|---|
| 테스트 숙소 안내 — `11140466` 은 GBP | **USD** 다. `connection_details.currency` 와 만든 채널의 `currency` 둘 다 USD. GBP 요금제로 만들었다가 USD 숙소·요금제를 새로 만들어 옮겼다 |
| 채널 API 문서 — 어댑터 코드 `booking_com` | `GET /channels/adapter?code=booking_com` 은 400. **`BookingCom`** 이 맞다(테스트 숙소 안내의 예시 코드) |
| 예약 피드 순서 "적혀 있지 않다"(작업지시-17 2절 C) | `GET /booking_revisions/feed` 의 `meta` 가 `order_by: inserted_at, order_direction: asc, limit: 10` 이다 — **오름차순으로 준다.** 그래도 한 묶음 안에서 정렬해 넘긴다 |
| `channel_restrictions` 의 뜻(6절이 모른다고 함) | `{currency: EUR, min_price: 500}` — 채널이 받는 최소 요금 5.00 EUR 로 읽힌다(정수 = 최소 단위). 확정은 브랜치 2 의 요금 전송에서 |
| `rate_params.pricing_type` 기본값 | `"Standart"`(오타). 값은 `Standard`|`OBP` 를 보내야 한다 |

## 5. 시간과 레이트 리밋

(브랜치 2·3 에서 채운다 — 걸린 시간, 429 를 받았는지, 200+경고를 받았는지)
