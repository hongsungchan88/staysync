# Mock OTA 시뮬레이터

**우리 앱이 아니라 상대역이다.** 실제 OTA 에서 일어나지만 재현하기 어려운 악조건을
만들어 내는 별도 애플리케이션이고, 12~13주차에 채널 어댑터와 동기화 워커가 이것을
두드린다. 설계 근거는 `docs/adr/0011-mock-ota-시뮬레이터.md`.

`com.staysync` 의 어떤 타입도 참조하지 않는다. 주고받는 것은 JSON 뿐이다.

## 띄우기

```bash
./gradlew runSimulator          # 8081 포트. 저장소 루트에서 부른다
```

`bootRun` 이 아니다. 이름이 겹치면 `./gradlew bootRun` 이 백엔드와 시뮬레이터를 함께
띄우려 든다.

## 엔드포인트

모든 `/api/**` 요청에 헤더 `X-Api-Key` 가 필요하다. 없거나 틀리면 401 이다.
기본 키는 `application.yml` 의 `mock-ota-dev-key`.

| 메서드 | 경로 | 하는 일 |
|---|---|---|
| `POST` | `/api/ari` | ARI 를 받아 그대로 쌓는다. 해석하지 않는다. 202 |
| `GET` | `/api/ari` | 받은 것을 보낸 순서대로 돌려준다 |
| `DELETE` | `/api/ari` | 받은 것을 전부 잊는다. 재동기화 배치 검증용 |
| `GET` | `/api/bookings` | 만들어 둔 예약 목록. 폴링 수신 경로 |
| `POST` | `/api/scenarios/duplicate` | 같은 예약을 여러 번. 예약번호까지 같다 |
| `POST` | `/api/scenarios/burst` | 동시 예약 다발. 같은 객실·날짜를 두고 경쟁한다 |
| `POST` | `/api/scenarios/revision-reorder` | 높은 revision 먼저, 낮은 것 나중 |
| `POST` | `/api/scenarios/overbook` | 재고를 무시하고 같은 날짜에 여럿 |
| `GET` | `/api/messages` | 게스트가 보낸 메시지. 우리 쪽에서 보면 수신이다 |
| `POST` | `/api/messages` | 우리가 보낸 메시지를 받는다. 202 |
| `GET` | `/api/messages/sent` | 우리가 보낸 것을 되돌려 준다 |
| `DELETE` | `/api/messages` | 주고받은 것을 전부 잊는다 |
| `POST` | `/api/scenarios/guest-message` | 게스트 메시지. 같은 식별자로 `count` 번, 시각은 거꾸로 |

메시징 경로도 `/api` 아래라 자격 증명 검사와 악조건 주입을 그대로 받는다. 메시징만
무사통과시키면 발송 실패의 재시도·`DEAD` 처리를 검증할 수 없다.

**본문을 해석하지 않는다.** 비었는지, `{{guestName}}` 이 남아 있는지 보지 않는다.
막는 것은 우리 쪽 일이고, 여기서 걸러 주면 그 검증이 사라진다.

시나리오 본문은 전부 선택이다. `{}` 로 불러도 기본값으로 돈다.
JSON 은 `snake_case` 다 — `booking_id`, `room_id`, `check_in`, `check_out`, `count`.

```bash
curl -X POST http://localhost:8081/api/scenarios/overbook \
  -H 'X-Api-Key: mock-ota-dev-key' -H 'Content-Type: application/json' \
  -d '{"room_id":"room-1","check_in":"2026-12-24","check_out":"2026-12-26","count":3}'
```

게스트 메시지 하나를 손으로 만들어 볼 때는 이렇게 부른다. 예약이 없어도 된다 —
스레드는 숙소에 붙고, 나중에 예약이 들어오면 이어 붙는다(`MessageIngestService`).

```bash
curl -X POST http://localhost:8081/api/scenarios/guest-message \
  -H 'X-Api-Key: mock-ota-dev-key' -H 'Content-Type: application/json' \
  -d '{"booking_id":"BK-INBOX-1","body":"체크인 시간을 늦출 수 있을까요?","count":3}'
```

`thread_id` 는 `thread-<booking_id>`, `message_id` 는 `msg-<booking_id>` 로 만들어진다.
셋을 보내도 우리 쪽 받은편지함에는 **한 통**이어야 한다.

예약이 생기면 `mockota.webhook-url` 로 POST 도 나간다. 비어 있으면 보내지 않는다.
**폴링과 웹훅의 본문 형식은 같다** — 어댑터가 매핑을 두 벌 갖지 않게 하려는 것이다.

## 악조건 주입

프로퍼티로만 켠다. 런타임에 바꾸는 엔드포인트는 없다.

```bash
./gradlew runSimulator --args='--mockota.chaos.error-rate=0.05 --mockota.chaos.seed=42'
```

| 프로퍼티 | 뜻 |
|---|---|
| `mockota.chaos.latency-ms` | 응답을 이만큼 늦춘다. 타임아웃·서킷브레이커 검증용 |
| `mockota.chaos.error-rate` | 이 비율로 503 을 낸다. 재시도·백오프 검증용 |
| `mockota.chaos.seed` | 시드. **주면 실패 순서가 재현된다** |

**악조건은 `/api/ari` 와 `/api/bookings` 에만 걸린다.** 시나리오 경로는 채널의 표면이
아니라 테스트가 쥐는 손잡이라 지나간다 — 손잡이까지 실패시키면 에러율 100% 에서
시험 준비 자체가 불가능해진다.

시드를 주지 않으면 매번 새로 뽑고 그 값을 기동 로그에 남긴다. 우연히 만난 실패를
나중에 다시 만들 수 있어야 하기 때문이다.

## 시뮬레이터는 나쁘게 군다

**중복을 막지 않고 순서를 바로잡지 않는다.** 같은 예약번호로 두 번 보내면 두 건이
되고, revision 2 다음에 1 을 보낸다. 여기서 바로잡고 싶어지면 방향을 거꾸로 잡은
것이다 — 막는 것은 우리 쪽 일이고, 그게 실제로 막는지 보려고 나쁘게 구는 상대가
필요해서 이걸 만든다.

## 상태

메모리에만 있다. 데이터베이스가 없고 재기동하면 비어도 된다.
