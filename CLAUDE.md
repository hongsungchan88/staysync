# StaySync

부업으로 자기 공간의 일부를 숙소로 내놓는 개인 호스트를 위한 숙박 채널·운영 통합 관리 시스템.
여러 OTA에 흩어진 예약과 요금을 한 곳에서 다루고, 게스트 응대와 요금 결정을 AI가 보조한다.

1인 개발, 2026년 8월 ~ 2027년 1월, 주당 20시간 기준 총 460시간.
상세 계획은 `docs/CMS-PMS-프로젝트-기획서.md`, 데이터 모델 근거는 `docs/결정문서-01-데이터모델.md`에 있다.

## 명령

```bash
./gradlew bootRun --args='--spring.profiles.active=local'   # 실행 (Docker 불필요)
./gradlew test                                              # 전체 테스트
./gradlew build                                             # 컴파일 + 테스트
./gradlew resetLocalDb                                      # 로컬 DB 초기화 (평소엔 불필요)
```

## 스택

Java 21 · Spring Boot 3.5.4 · Spring Modulith 1.3 · PostgreSQL 16 · Flyway · Gradle 9 · JUnit 5

프론트엔드는 아직 없다. P2(9월 말)에 React 19 + Vite로 시작한다.

## 실행 프로파일

| 프로파일 | 데이터베이스 | 락 | 벡터 검색 | Docker |
|---|---|---|---|---|
| `local` (기본) | 내장 PostgreSQL 16 (zonky embedded-postgres) | JVM 내부 | 메모리 코사인 | 불필요 |
| `docker` | PostgreSQL 16 + pgvector + Redis | Redis | pgvector HNSW | 필요 |

`local`은 `clean-on-start: true`라 기동할 때마다 DB를 새로 만든다. 스키마가 자주 바뀌는 지금은
이게 맞다. Flyway 체크섬 오류를 만날 일이 없다. P2 무렵 스키마가 안정되면 끈다.

H2를 쓰지 않는 이유는 마이그레이션을 두 벌 유지해야 하고, `FOR UPDATE SKIP LOCKED`와
생성 컬럼, 부분 인덱스가 동작하지 않아 정작 검증할 것을 검증하지 못하기 때문이다.

## 모듈 경계 (반드시 지킬 것)

최상위 패키지 하나가 모듈 하나다. `shared`만 모든 모듈이 참조할 수 있다.

```
shared  identity  property  booking  pricing  channel  messaging  ops  payment  ai  analytics
```

**모듈 간 직접 호출 금지.** 다른 모듈을 쓸 때는 그 모듈이 최상위 패키지에 공개한 인터페이스만
참조한다. 하위 패키지(`property.domain` 등)는 모듈 내부 구현이다.

예를 들어 booking이 판매 단위 수량을 알아야 할 때, `property.domain.Unit` 엔티티를 직접 쓰지 않고
`property.UnitCatalog` 인터페이스를 쓴다. 의존 방향은 booking → property다. 반대 방향으로
포트를 두면 안 된다.

`ModularityTest`가 위반을 잡는다. 이 테스트가 깨지면 설계가 틀린 것이지 테스트가 틀린 게 아니다.

## 데이터 모델

`Property → Unit → RatePlan` **2계층**이다. 객실타입과 개별 객실을 나누지 않는다.

개인 호스트에게는 판매 상품이 곧 물리 공간이라 두 계층이 항상 1:1이 된다. 도미토리처럼 같은
조건의 자리를 여러 개 파는 경우는 `Unit.totalUnits`로 표현한다. 호실 배정(배방) 기능은 없다.
이건 검토를 거쳐 확정한 결정이니 되돌리지 말 것. 근거는 `docs/결정문서-01-데이터모델.md`.

`RatePlan`은 개인 호스트에게 의미가 없어 보여도 **지운다는 판단은 이미 기각했다.**
Booking.com과 Channex가 ARI 전송의 최소 단위로 `rate_plan_id`를 요구한다. 없애면 채널
어댑터마다 가짜 식별자를 끼워 넣는 우회 코드가 생긴다. 대신 `UnitRegistrationService`가
판매 단위를 만들 때 기본 요금제를 자동 생성해 화면에서는 숨긴다.

## 인증

액세스 토큰은 HS256으로 서명한 JWT(30분), 리프레시 토큰은 `refresh_token` 테이블에
SHA-256 해시로 저장하는 난수(14일)다. 갱신할 때마다 회전시키고, 이미 교체된 토큰이
다시 들어오면 도난으로 보고 그 사용자의 토큰을 전부 무효화한다. 발급과 검증은
`spring-boot-starter-oauth2-resource-server`(Nimbus)에 맡기고 직접 만들지 않는다.
서명 키는 환경 변수 `JWT_SECRET`이며 없으면 기동이 막힌다(local 프로파일만 예외).
근거는 `docs/adr/0004-jwt-인증.md`와 `docs/adr/0005-리프레시-토큰-회전.md`.

액세스 토큰은 응답 본문 JSON으로, 리프레시 토큰은 쿠키로 내보낸다
(`HttpOnly; Secure; SameSite=Strict; Path=/api/auth`). 액세스는 헤더에 실어야 하니
자바스크립트가 읽어야 하지만 리프레시는 읽을 이유가 없다. `Secure`는 local에서만 끈다.
근거는 `docs/adr/0006-토큰-전달-방식.md`.

인증 결과는 `shared.security.AuthenticatedUser`로 `SecurityContext`에 담긴다.
다른 모듈은 이 타입만 읽고 identity를 참조하지 않는다. 조회를 조직 단위로 좁힐 때는
반드시 여기의 `orgId`를 쓴다. 요청 본문이나 경로의 조직 식별자를 믿으면 안 된다.
`unit`과 `rate_plan`에는 `org_id`가 없어 `property`까지 거슬러 올라가야 하는데,
그 확인은 `property.OwnedResources` 한 곳에 모아 뒀다. 컨트롤러는 반드시 이걸 거친다.

**재사용 탐지 같은 "실패하면서 기록을 남기는" 처리는 별도 트랜잭션이어야 한다.**
같은 트랜잭션에서 무효화하고 예외를 던지면 롤백이 무효화까지 되돌린다.
`CompromisedTokenHandler`를 따로 둔 이유다.

## 중복예약 방어 (프로젝트의 핵심)

네 계층으로 막는다. 하나라도 빼지 말 것.

| 계층 | 방식 | 차단 대상 |
|---|---|---|
| 1 | `UnitLock` (JVM 내부, 나중에 Redis) | 애플리케이션 수준 동시 진입 |
| 2 | `SELECT ... FOR UPDATE`, **날짜 오름차순** | DB 동시 갱신, 교착 상태 |
| 3 | `CHECK (booked + held <= total)` | 로직 결함이 통과했을 때 |
| 4 | 충돌 감지 절차 | iCal 지연 등 원천 차단 불가 |

지켜야 할 규칙 세 가지:

- **락 획득은 항상 날짜 오름차순.** 스레드마다 순서가 다르면 교착 상태가 난다.
  `StayPeriod.nightDates()`가 정렬을 보장하니 이걸 우회하지 말 것.
- **락을 먼저 잡고 그 안에서 트랜잭션을 연다.** 순서가 반대면 트랜잭션이 열린 채 락을
  기다려 커넥션 풀이 마른다. 이 때문에 `InventoryService`(락)와 `InventoryLedgerWriter`(트랜잭션)를
  다른 빈으로 분리했다. 같은 클래스 안에서 `@Transactional` 메서드를 호출하면 프록시를
  거치지 않아 트랜잭션이 아예 걸리지 않는다.
- **OTA에서 이미 성사된 예약은 거절하지 않는다.** 재고가 없어도 받아들이고
  `overbooking_conflict`에 기록해 운영자가 해소하게 한다. 거절하면 게스트와 플랫폼
  양쪽에서 문제가 된다.

## 채널 연동

`ChannelAdapter` 인터페이스 하나 뒤에 iCal, Channex, Mock 세 구현을 감춘다.
도메인 로직은 어떤 채널과 이야기하는지 몰라야 한다.

- 어댑터는 `capabilities()`로 지원 기능을 알린다. iCal은 `PUSH_RATE`를 지원하지 않으므로
  요금을 바꿔도 전파 작업을 만들지 않고 화면에 표시만 남긴다.
- 예약 수신 멱등성은 `(channel_code, channel_booking_id)` 유니크 제약으로 보장한다.
  같은 웹훅이 세 번 와도 예약은 한 건이어야 한다.
- 예약 수정은 `revision` 비교로 순서 역전을 막는다. 낮은 버전이 나중에 도착하면 무시한다.
- Channex 제한은 숙소당 분당 20회다. 요금·제약 10회와 재고 10회가 따로 매겨진다.
  초과하면 429가 오며, 그 숙소의 전송을 1분 멈춘 뒤 지수 백오프로 재시도한다.
  변경을 6초 윈도로 모으고 연속된 같은 값은 날짜 구간으로 압축해서 보낸다.
  본문을 10MB까지 받으므로 압축 목표는 6개월치 변경이 호출 한 번으로 끝나는 것이다.
  Channex 자가 인증 항목 8이 그 기준이다. 근거는 `docs/조사-01-channex-샌드박스.md`.
- Channex 스테이징(`staging.channex.io`)은 무료이고 구독 없이 API 키가 나온다.
  부킹닷컴 공용 테스트 숙소가 준비되어 있어 Mock 이 아닌 실제 채널로 검증할 수 있다.

## 마이그레이션

- `db/migration/postgresql/V1__init.sql` — 기본 스키마 23개 테이블. 두 프로파일 모두 적용.
- `db/migration/pgvector/V2__pgvector.sql` — 확장 설치와 벡터 타입 전환. `docker`만 적용.

내장 PostgreSQL 바이너리에 pgvector가 없어서 나눴다. **V1에 `CREATE EXTENSION vector`나
`vector(1536)` 타입을 넣지 말 것.** 로컬에서 기동이 실패한다.

`ddl-auto: validate`라 엔티티와 스키마가 어긋나면 기동이 막힌다. Hibernate는 JDBC 타입
코드로 비교한다. `CHAR`(1)와 `VARCHAR`(12)는 코드가 달라 실패하고, `TEXT`는 드라이버가
`VARCHAR`로 보고하므로 `String`과 호환된다. 컬럼을 추가할 때 이걸 확인할 것.

## 코딩 규칙

- 주석과 문서는 한국어. 식별자는 영어.
- 주석은 "무엇을"이 아니라 "왜"를 적는다. 코드를 읽으면 아는 것은 쓰지 않는다.
- 테스트 메서드 이름은 한국어로 서술형. 예: `재고가_하나면_동시요청_백건_중_한건만_성공한다`
- 엔티티는 setter를 두지 않는다. 상태 변경은 의미 있는 메서드로 표현한다
  (`cancel()`, `promoteHold()`, `markDirty()`).
- 도메인 예외는 `shared.error.DomainException`을 상속하고 코드와 HTTP 상태를 갖는다.
- 새 결정을 내렸으면 `docs/adr/`에 기록을 남긴다. 기존 3건의 형식을 따른다.

## 브랜치 전략

`main` 하나를 항상 실행 가능한 상태로 유지한다.

- 여러 커밋이 필요한 작업은 브랜치에서 한다. 접두어는 `feat/` `fix/` `refactor/` `docs/`.
  자르는 단위는 단계(P1~P6)가 아니라 며칠짜리 작업이다. 단계로 자르면 브랜치가 몇 주씩
  살아남아 병합할 때 감당이 안 된다.
- 한 커밋으로 끝나는 문서 수정이나 설정 변경은 `main`에 직접 한다.
- 병합은 `git merge --no-ff`로 한다. 빨리감기로 합치면 어디부터 어디까지가 한 작업이었는지
  이력에서 사라진다.
- 단계가 끝나면 태그를 남긴다. 예: `p1-complete`
- 작업을 시작할 때 브랜치가 필요한지 먼저 판단한다. 필요하면 만들고 시작한다.

## 하지 말 것

- 모듈 경계를 넘는 직접 호출. `ModularityTest`가 실패한다.
- `ddl-auto`를 `update`나 `create`로 바꾸기. 스키마는 Flyway가 관리한다.
- 이미 적용된 마이그레이션 파일 수정. 새 버전을 추가한다.
  (단 스키마가 안정되기 전인 지금은 V1 수정 + `clean-on-start`로 처리 중이다.)
- LLM에게 금액을 계산시키기. 요금은 규칙 엔진이 산출하고 LLM은 설명만 한다.
- AI 코파일럿에 쓰기 도구 제공. 조회와 제안까지만 한다.
- 스크래핑이나 비공개 API 역공학. 학술 프로젝트로서 지키기로 한 선이다.

## 현재 상태

**끝난 것** — 데이터 모델 확정과 실행 검증, 재고 방어 계층 구현, `ChannelAdapter`
인터페이스, ADR 6건. P1 3주차: JWT 인증(가입·로그인·갱신·로그아웃·시도 제한),
리프레시 토큰 회전과 재사용 탐지, 숙소·판매 단위·요금제 REST API와 조직 스코핑.
테스트 44건 통과.

**다음** — P1 4주차. 예약 애그리게이트와 상태 머신, 수기 예약 처리.
역할별 인가 규칙(`@PreAuthorize`)은 아직 없다. 지금은 인증 여부와 조직 스코핑까지다.

**아직 비어 있는 모듈** — pricing, messaging, ops, payment, ai, analytics.
각 패키지의 `package-info.java`에 담당 범위와 착수 시점을 적어뒀다.

## 겪은 함정

같은 실수를 반복하지 않도록 남긴다.

- `@OneToMany(mappedBy = "...")`의 대상은 연관 필드여야 한다. 스칼라 `Long` 필드를 가리키면
  기동이 실패한다.
- 테스트는 앱과 다른 포트·데이터 디렉터리를 쓴다(15433, `.localdb-test`). 안 그러면 앱을
  띄운 채 테스트를 돌릴 때 포트가 충돌한다.
- 트랜잭션이 롤백되면 그 안에서 만들어진 행도 사라진다. 검증 쿼리는 빈 결과를 견뎌야 한다.
- IntelliJ에서 앱을 강제 종료하면 자식 프로세스 postgres가 남아 `.localdb`를 잡는다.
  `Get-Process postgres | Stop-Process -Force`로 정리한다.
