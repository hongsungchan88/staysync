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

## 2. 재고·요금 전송 (브랜치 2, `feat/channex-ari`, 09-22)

### 2.1 스테이징이 실제로 하는 일 (09-22 04:00 UTC, 파이썬 직접 호출)

**쓰기.** `POST /api/v1/availability {"values":[{"property_id","room_type_id","date_from","date_to","availability"}]}` → 200
`{"data":[{"id":"<task uuid>","type":"task"}],"meta":{"message":"Success"}}` (1.35s).
`POST /api/v1/restrictions {"values":[{"property_id","rate_plan_id","date_from","date_to","rate":"120.00","min_stay_arrival":2,"stop_sell":false}]}`
→ 같은 모양 (0.95s). `date_from`~`date_to` 는 **양끝 포함**이다(10-22~10-25 로 보내면 되읽기에 나흘이 있다).

**되읽기.** `GET /api/v1/availability?filter[property_id]=…&filter[date][gte]=…&filter[date][lte]=…` →
`{"data":{"<room_type_id>":{"2026-10-22":1,…}}}`.
`GET /api/v1/restrictions?…&filter[restrictions]=availability,rate,min_stay_arrival,min_stay_through,stop_sell,closed_to_arrival,closed_to_departure,max_stay` →
`{"data":{"<rate_plan_id>":{"2026-10-22":{"availability":1,"closed_to_arrival":false,"closed_to_departure":false,"max_stay":0,"min_stay_arrival":2,"min_stay_through":1,"rate":"120.00","stop_sell":false,"unavailable_reasons":[]},…}}}`.
**요금제 키 아래 재고까지 같이 오므로 재동기화는 이 GET 하나로 대조한다.** 대괄호를 `%5B`/`%5D` 로 인코딩해 보내도(RestClient 가 그렇게 한다) 200 이다.

**실제로 받은 경고(전부 200).** 그대로 `ChannexStubServer.Responses` 에 넣어 테스트 픽스처로 쓴다.

| 보낸 것 | 응답 |
|---|---|
| 지난 날짜 `date: 2026-09-21` | `{"data":[],"meta":{"message":"Success","warnings":[{"warning":{"date":["Past date is not allowed"]},"date":"2026-09-21","property_id":"…","rate_plan_id":"…","rate":"120.00"}]}}` |
| `rate: "0"` | `warnings:[{"warning":{"rate":["must be greater than 0"]},"date":"2026-10-22",…,"rate":"0"}]` |
| `availability: -1` | `warnings:[{"warning":{"availability":["must be greater than or equal to 0"]},…,"availability":-1}]` |
| 없는 `room_type_id` | `warnings:[{"warning":"Not found room_type for this change","date":"2026-10-22",…}]` — **경고가 객체가 아니라 문자열** |
| 빈 본문 `{"property_id":…}` 만 | `warnings:[{"warning":{"date":["Should be included field date or fields pair date_from-date_to"],"room_type_id":["can't be blank"],"availability":["can't be blank"]},…}]` — **400 이 아니다** |
| 틀린 키 | **401** `{"errors":{"code":"unauthorized","title":"Unauthorized"}}` |

**레이트 리밋.** 응답 헤더 `ratelimit-policy: "availability";q=6000;w=60, "availability";q=360000;w=3600, "availability";q=8640000;w=86400`,
`ratelimit: "availability";r=5996;t=21, …`. **분당 6,000** 이다 — 문서(조사-01)의 "숙소당 분당 10" 과 다르다. 12.5초에 15번을
보내도 전부 200 이라 **실제 429 는 받지 못했다.** 429 처리는 `Retry-After` → `ratelimit` 의 `t=` → 60초 순으로 대기 시간을
읽게 짜고 스텁으로만 검증했다(완료 조건 6 은 스텁 + 실측 헤더).

### 2.2 StaySync 를 거친 왕복 (로컬 앱 → 스테이징, 09-22 13:1x KST)

로컬 조직 `Channex 시험 조직`(숙소 `Channex 시험 숙소 (USD)`, 판매 단위 `시험 객실` 1실 기본 요금 100), CHANNEX 연결(채널 코드
`BOOKING_COM`, `api_key`+`property_id`, 응답은 `UspI••••589x`/`17e7••••c8f3` 로 가려짐), 매핑 `92f88770… / 46b69549…`.

| 한 일 | Channex 되읽기 | 걸린 시간 |
|---|---|---|
| 30일 일괄 편집 요금 130·최소 숙박 2 (11-01~11-30) | 첫날·중간·마지막 전부 `rate "130.00", min_stay_arrival 2` | **14.6s**(병합 버퍼 6s + 워커 주기) |
| 30일 일괄 편집 요금 140 (12-11~01-09) | 마지막 날 `140.00` | **11.5s**. 앱 로그 `Channex 전송. path=/api/v1/restrictions 값=1건 task=…` **한 줄** — **요청 1번, 값(구간) 1개**(완료 조건 5) |
| 수기 예약 2박 → 취소 | 예약 뒤 `availability 0`(이미 0 이었다 — 우리가 재고를 보낸 적 없는 날은 Channex 가 0 이다), **취소 뒤 `availability 1, stop_sell false`** | 12s 안 |
| 다시 예약 1박 | `availability 0` — **`stop_sell true` 는 Channex 가 스스로 켠다**(우리는 `false` 를 보냈다) | **11.5s** |

완료 조건 3·4 판정. 부킹닷컴 테스트 예약을 만들 재고가 이제 있다(브랜치 3).

### 2.3 StaySync 쪽 (브랜치 2)

- `ChannexAdapter.pushAri` — 세그먼트를 재고(`/availability`, `room_type_id`)와 요금·제약(`/restrictions`, `rate_plan_id`)으로 갈라
  두 번 보낸다. 요금은 `"120.00"` 문자열, `min_stay_arrival`·`min_stay_through` 둘 다(숙소 `min_stay_type: both`). 채널로 보내는
  값은 `InventoryLedger.available()`(0 아래로 안 내려감) — **초과분을 더해 보내지 않는다**(9.2 B, `ChannexAriTest` 가 초과 예약 둘인
  날에 `availability: 0` 이 나가는 것을 본다)
- `meta.warnings` 가 하나라도 있으면 `PermanentChannelException` → 워커가 **DEAD** + `last_error` 에 경고 문장(완료 조건 7)
- 401/403/404 → 영구, 5xx·연결 실패 → 일시, 429 → `RateLimitedException(Retry-After | ratelimit t= | 60s)`. 워커의 연결별 순서
  보장(`NOT EXISTS`)이 "그 숙소만 멈춘다"를 만든다 — 이웃 연결은 그대로 나간다(완료 조건 6)
- `fetchAriSnapshot(creds, room, rate, from, to)` — `ChannelAdapter` 에 요금제까지 받는 꼴을 더했고(기본 구현은 옛 셋짜리로 넘김)
  `ChannelReconcileJob` 이 그걸 부른다. Channex 는 `GET /restrictions` 한 번으로 재고·요금·최소 숙박·판매중지를 읽는다
- **판매 단위 수량 변경(`/capacity`)이 채널로 나간다**(9.2 D). `UnitCapacityWriter` 가 `UNIT_CAPACITY_CHANGED`(unitId, 오늘~+180일)를
  Outbox 에 남기고 `ChannelSyncService` 가 재고 이벤트로 받는다. 안 보내면 새벽 4시 재동기화까지 채널이 옛 수량으로 판다
- `AdapterType.CHANNEX` 선언: `PUSH_AVAILABILITY`·`PUSH_RATE`·`PUSH_RESTRICTION`

## 3. 예약 피드 (브랜치 3, `feat/channex-booking-feed`, 09-22)

### 3.1 부킹닷컴 실물은 못 받았다 — 채널이 회수됐다

09-22 19:00 KST, 사용자가 부킹닷컴 테스트 숙소 11140466 에 예약(11-10~12, "Te st", US$361.08)을 만들었으나 피드·
`booking_revisions`·`bookings` 전부 0건. **우리 채널 `d9283bf0…` 이 지워져 있었다**(`GET /channels/:id` 404, 채널 목록 빈
배열, 숙소 `acc_channels_count 0`). 09-22 04:00 UTC 재고 전송 실측 때는 살아 있었으니 그 뒤 15시간 안에 회수됐다
(`expected_removal_date` 는 `null` 이었다). 그 사이 만든 부킹닷컴 예약은 우리에게 오지 않는다.
다시 만들려 했으나 11140466·10485037(USD)·5868189·6519420(GBP)·4372137(EUR) 전부 422 "already exists". 5분 간격으로
1시간 재시도했고 그 안에 풀리지 않았다(3.4). **심사 시연 전에는 채널 생존 확인이 선행 조건이다.**

### 3.2 Booking CRS 로 실물을 만들었다 (Channex 인증 시험 11 이 허용하는 경로)

`POST /api/v1/applications/install {"application_installation":{"property_id":…,"application_code":"booking_crs"}}` → 200
(설치 `19cf28ed-…`, USD 시험 숙소). 그 뒤:

| 호출 | 본문 요지 | 응답 |
|---|---|---|
| `POST /api/v1/bookings` | `ota_name "Booking.com"`, `ota_reservation_code "STAYSYNC-CRS-001"`, 11-10→11-12, USD, 방 하나(`days {"2026-11-10":"180.54","2026-11-11":"180.54"}`, 성인 2), 손님 "Te st" | 200 `{"data":{"attributes":{"id":"87358c26-…","status":"new","unique_id":"BDC-STAYSYNC-CRS-001","booking_id":"87358c26-…","revision_id":"22861a65-…"}}}` |
| `GET /booking_revisions/feed?filter[property_id]=…` (2초 뒤) | | **`total 1`** — 리비전 `22861a65-…`, `status new`, `amount "361.08"`, `currency USD`, `acknowledge_status "pending"`, `is_crs_revision true`, `channel_id null`. 방에 `checkin_date`·`checkout_date`·`amount "361.08"`·`days`·`is_cancelled false`. **CRS 예약도 피드에 뜬다**(문서엔 명시 없음, 실측) |
| `PUT /api/v1/bookings/:id` `status "modified"`, 11-10→11-13(3박) | | 200, 새 리비전 `2babc335-…` `modified`, `amount "541.62"` |
| `PUT /api/v1/bookings/:id` `status "cancelled"` | | 200, 새 리비전 `4c0301c9-…` `cancelled`(방 `is_cancelled` 는 `false` 그대로, 예약 금액도 그대로 실린다) |
| `GET /booking_revisions/:id` (ack 뒤) | | `acknowledge_status "acknowledged"`, 피드 `total 0` |

`raw_message` 안에는 금액이 **최소 단위 정수**(`"days":{"2026-11-10":18054}`, `"amount":0`)로 남아 있고, 속성에는 `"180.54"`·
`"361.08"` 문자열이다 — 어댑터는 속성을 읽는다. 첫 리비전 실물이 `ChannexStubServer.Responses.REVISION_CRS_NEW` 다(계정
식별자 `system_id` 는 0 으로, 손님은 가명 그대로).

### 3.3 StaySync 를 거친 왕복 (로컬 앱, 60초 폴링)

| 한 일 | StaySync | Channex |
|---|---|---|
| 생성 리비전 | 첫 폴링에 들어옴. 예약 83 `CONFIRMED`, **채널 코드 `BOOKING_COM`**, `totalAmount 361.08`(USD 숫자 그대로 — 미상 아님), 손님 "Te st", 성인 2. 캘린더 `avail` 11-10·11 = 0. 리포트(11월) `channelMix [{BOOKING_COM, 1건, 361.08, 73.5%}, {DIRECT, …}]`, `unknownAmount 0` | 피드 0건, 리비전 `acknowledged` (ack 는 커밋 뒤). 재고 11-10·11 = 0 (Channex 가 생성 때 스스로 줄인다) |
| 변경(3박) | 22초 안에 `checkOut 11-13`, `totalAmount 541.62`, 11-12 도 0 | ack 됨. **11-12 는 1 그대로** — `allow_availability_autoupdate_on_modification: false`(숙소 기본값)이고 우리는 원 채널에 되보내지 않았다 |
| 취소 | 38초 안에 `CANCELLED`, 11-10~12 전부 1 | ack 됨. **11-10·11 이 0 으로 남았다** — `…_on_cancellation: false`. 부킹닷컴이 계속 닫혀 있게 되는 결함 |
| **고친 뒤 두 번째 주기**(11-20~22 생성 → 취소) | 생성 16초, 취소 57초 안 | **취소 69초 뒤 11-20·21 = 1 로 돌아왔다** — `ChannelSyncService` 가 Channex 에는 원 채널이어도 되보낸다(아래) |

**되울림 예외.** 12주차의 "자기가 만든 예약을 자기에게 되보내지 않는다"는 OTA 용이다 — OTA 는 자기 예약을 스스로 깎는다.
Channex 는 OTA 가 아니라 우리 원장의 거울이고, 실측으로는 생성 때만 스스로 줄이고 변경·취소에는 손대지 않는다. 그래서
`adapterType == CHANNEX` 면 원 채널이어도 재고를 보낸다. 같은 값을 한 번 더 보내는 비용(요청 1)뿐이다.

**완료 조건 18 의 나머지(막대 색이 부킹닷컴 색인지)** — 캘린더 API 가 막대에 `channel: BOOKING_COM` 을 싣는 것까지 봤다.
라벨·색은 `frontend/src/calendar/channels.ts` 의 `BOOKING_COM` 항목이 맡고 단위 테스트가 덮는다. **브라우저로 실제 색을 보는
것은 부킹닷컴 실물과 함께 심사 직전 1회로 미룬다**(8.3).

### 3.4 USD 숙소 재시도

09-22 19:13~20:1x KST, 10485037·11140466 에 5분마다 `POST /channels` — 전부 422. 회수는 주기적으로 일어나므로 심사 직전에
다시 시도한다(채널 생존 확인 → 재고 푸시 → 앱 기동 → 예약 → 변경 → 취소를 한 자리에서).

## 4. Channex 문서와 실제가 다른 곳

| 문서 | 실제 (09-21) |
|---|---|
| 테스트 숙소 안내 — `11140466` 은 GBP | **USD** 다. `connection_details.currency` 와 만든 채널의 `currency` 둘 다 USD. GBP 요금제로 만들었다가 USD 숙소·요금제를 새로 만들어 옮겼다 |
| 채널 API 문서 — 어댑터 코드 `booking_com` | `GET /channels/adapter?code=booking_com` 은 400. **`BookingCom`** 이 맞다(테스트 숙소 안내의 예시 코드) |
| 예약 피드 순서 "적혀 있지 않다"(작업지시-17 2절 C) | `GET /booking_revisions/feed` 의 `meta` 가 `order_by: inserted_at, order_direction: asc, limit: 10` 이다 — **오름차순으로 준다.** 그래도 한 묶음 안에서 정렬해 넘긴다 |
| `channel_restrictions` 의 뜻(6절이 모른다고 함) | `{currency: EUR, min_price: 500}` — 채널이 받는 최소 요금 5.00 EUR 로 읽힌다(정수 = 최소 단위). 확정은 브랜치 2 의 요금 전송에서 |
| `rate_params.pricing_type` 기본값 | `"Standart"`(오타). 값은 `Standard`|`OBP` 를 보내야 한다 |
| 레이트 리밋 "숙소당 분당 요금 10·재고 10"(조사-01, 문서) | 스테이징 헤더는 **분당 6,000** (`ratelimit-policy`). 실제 429 를 못 만들었다 |
| 잘못된 요청은 400 | **전부 200 + `meta.warnings`** 다. 빈 본문도 200 이다. 400 은 한 번도 못 봤다 |
| 재고 0 이면 판매중지? | 재고를 0 으로 보내면 Channex 가 `stop_sell: true` 를 **스스로 켠다**(우리는 `false` 를 보냈다). 되읽기로 판매중지를 대조할 때 이걸 감안해야 한다 |
| 레이트 리밋 상한 | **스테이징 관측값(6,000/분)과 우리 상한(숙소당 분당 재고 10·요금/제약 10, `ChannexSendLimiter`)이 다르다. 우리는 문서 값을 쓴다** — 운영이 더 엄격할 수 있고 상용 인증 기준이 문서 쪽이다 |
| 공용 테스트 채널 | **예고 없이 회수된다**(`expected_removal_date null` 이었는데 15시간 안에 사라짐). 회수된 사이에 만든 부킹닷컴 예약은 우리에게 오지 않는다. **심사 시연 전 채널 생존 확인이 선행 조건** |
| 테스트 숙소 안내 표의 통화 | `11140466` 은 GBP 로 적혀 있지만 실제 USD. **표의 통화를 믿지 말고 `connection_details` 로 읽는다** |
| CRS 예약과 피드 | 문서는 "regular logic … web-hooks and availability changes" 까지만 적는다. **실측: 피드에 뜬다**(2초 안), `is_crs_revision true`, `channel_id null` |
| 변경·취소 때 Channex 재고 | 생성 때만 스스로 줄인다. 변경·취소는 숙소 설정 `allow_availability_autoupdate_on_modification/cancellation` 기본 `false` 라 그대로다. 우리가 되보낸다(3.3) |

## 5. 시간과 레이트 리밋

| | 값 |
|---|---|
| 스테이징 왕복 | 쓰기 0.7~1.5s, 읽기 0.7~0.9s(개발 PC → SJC) |
| 변경 → Channex 반영 | 11.5~14.6s (병합 버퍼 6s + 워커 주기 + 왕복) |
| 30일 요금 변경 | 요청 1번, 값 1개 |
| 429 | **못 받았다.** 정책 분당 6,000(헤더 실측). 15연속 200 |
| 200+경고 | 받았다 — 지난 날짜·0 요금·음수 재고·없는 객실·빈 본문 다섯 모양(2.1) |
| 리비전 → StaySync | 생성 16초, 변경 22초, 취소 38~57초(60초 폴링). CRS 생성 → 피드 2초 |
| 취소 → Channex 재고 복귀 | 69초(폴링 + 6초 버퍼 + 워커) |
