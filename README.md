# StaySync

개인 호스트를 위한 숙박 채널·운영 통합 관리 시스템.

여러 OTA에 흩어진 예약과 요금을 한 곳에서 다루고, 게스트 응대와 요금 결정을 AI가 보조한다.

- 개발 기간: 2026년 8월 ~ 2027년 1월
- 상세 계획: `docs/CMS-PMS-프로젝트-기획서.md`
- 데이터 모델 결정 근거: `docs/결정문서-01-데이터모델.md`

## 시작하기

### 필요한 것

- JDK 21
- Windows에서 `local` 프로파일을 쓰려면 Microsoft Visual C++ 2013 재배포 패키지
- Docker는 선택 사항이다 (`docker` 프로파일에서만 쓴다)

### 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

`local` 프로파일은 zonky embedded-postgres가 실제 PostgreSQL 16 바이너리를 내려받아
직접 구동한다. 첫 실행은 다운로드 때문에 1~2분 걸리고, 이후에는 몇 초면 뜬다.
데이터는 `.localdb/`에 남으므로 재기동해도 유지된다. 초기화하려면 그 디렉터리를 지운다.

Docker를 쓰려면:

```bash
docker compose up -d postgres redis
./gradlew bootRun --args='--spring.profiles.active=docker'
```

### 스키마를 바꿨을 때

`local` 프로파일은 `staysync.embedded-postgres.clean-on-start: true`가 기본이라,
기동할 때마다 데이터베이스를 새로 만들고 마이그레이션을 처음부터 다시 적용한다.
초기 개발 중에는 `V1__init.sql`이 자주 바뀌는데, 이 설정 덕분에 Flyway 체크섬
오류를 만날 일이 없다. 스키마가 안정되고 데이터를 유지하고 싶어지면 `false`로 바꾼다.

수동으로 지워야 할 때는 다음을 쓴다.

```bash
./gradlew resetLocalDb
```

`epg-lock` 파일을 지우지 못한다는 오류가 나면 내장 PostgreSQL이 아직 살아 있는
것이다. IntelliJ에서 실행 중인 애플리케이션을 정지한 뒤, 남은 프로세스를 정리한다.
JVM이 강제 종료되면 자식 프로세스인 postgres가 남는다.

```powershell
Get-Process postgres -ErrorAction SilentlyContinue | Stop-Process -Force
```

### 테스트

```bash
./gradlew test
```

`InventoryConcurrencyTest`가 중복예약 방지의 핵심 검증이다. 재고 1개에 동시 요청
100건을 넣어 정확히 1건만 성공하는지 확인한다. Docker 없이 돌아간다.

## 프로파일

| 프로파일 | 데이터베이스 | 락 | 벡터 검색 | Docker |
|---|---|---|---|---|
| `local` | 내장 PostgreSQL 16 | JVM 내부 | 메모리 코사인 계산 | 불필요 |
| `docker` | PostgreSQL 16 + pgvector | Redis | pgvector HNSW | 필요 |

두 프로파일 모두 PostgreSQL이므로 기본 스키마는 한 벌만 유지한다.
다만 내장 PostgreSQL 바이너리에는 pgvector가 들어 있지 않아서, 벡터 관련
DDL만 따로 떼어냈다.

| 위치 | 내용 | 적용 대상 |
|---|---|---|
| `db/migration/postgresql/V1__init.sql` | 기본 스키마 23개 테이블 | 두 프로파일 모두 |
| `db/migration/pgvector/V2__pgvector.sql` | 확장 설치, `embedding` 컬럼을 `vector(1536)`로 변경, HNSW 인덱스 | `docker`만 |

`local`에서 `knowledge_chunk.embedding`은 `REAL[]`이고, 코사인 유사도는 Java에서
계산한다. 청크가 수백 개 규모라 이것으로 충분하다. 두 프로파일은 서로 다른
데이터베이스를 쓰므로 마이그레이션 이력이 달라도 문제되지 않는다.

## 모듈 구조

최상위 패키지 하나가 모듈 하나다. 모듈 사이의 직접 호출은 금지하며,
공개 API는 모듈 최상위 패키지의 타입만이다.

| 모듈 | 책임 | 상태 |
|---|---|---|
| `shared` | 이벤트, 락, 오류 처리 (모든 모듈이 참조 가능) | 일부 구현 |
| `identity` | 계정, 조직, 역할, 인증 | 예정 (P1) |
| `property` | 숙소, 판매 단위, 요금제 | 일부 구현 |
| `booking` | 예약, 재고 원장 | 일부 구현 |
| `pricing` | 요금 캘린더, 판매 제약, 요금 규칙 | 예정 (P2) |
| `channel` | 채널 연결과 동기화 | 인터페이스만 |
| `messaging` | 통합 인박스, 자동 발송 | 예정 (P5) |
| `ops` | 청소·점검 태스크 | 예정 (P5) |
| `payment` | 결제, 환불 | 예정 (P5) |
| `ai` | 게스트 응대, 요금 추천 | 예정 (P4) |
| `analytics` | 지표 집계 | 예정 (P5) |

엔티티와 스키마의 어긋남은 `spring.jpa.hibernate.ddl-auto: validate`가 기동 시점에
잡는다. Hibernate는 JDBC 타입 코드로 비교하므로 `CHAR`와 `VARCHAR`처럼 코드가 다르면
실패하고, `TEXT`는 드라이버가 `VARCHAR`로 보고하므로 `String`과 호환된다.

모듈 경계 위반은 `ModularityTest`가 잡는다. 문서에 적은 규칙을 사람이 지키기를
기대하는 대신 빌드가 강제하게 만든 장치다.

## 데이터 모델

`Property → Unit → RatePlan` 2계층이다. 객실타입과 개별 객실을 나누지 않았다.
개인 호스트에게는 판매 상품이 곧 물리 공간이라 두 계층이 항상 1:1이 되기 때문이다.
도미토리처럼 같은 조건의 자리를 여러 개 파는 경우는 `Unit.totalUnits`로 표현한다.

요금제는 남겼다. Booking.com과 Channex가 ARI 전송의 최소 단위로 요금제 식별자를
요구하기 때문이다. 대신 판매 단위를 만들 때 기본 요금제를 함께 만들어 화면에서는 숨긴다.

자세한 비교와 근거는 `docs/결정문서-01-데이터모델.md`에 있다.

## 중복예약 방어

| 계층 | 방식 | 차단 대상 |
|---|---|---|
| 1 | `UnitLock` (JVM 내부 또는 Redis) | 애플리케이션 수준 동시 진입 |
| 2 | `SELECT ... FOR UPDATE`, 날짜 오름차순 | 데이터베이스 동시 갱신, 교착 상태 |
| 3 | `CHECK (booked + held <= total)` | 로직 결함이 통과했을 때 |
| 4 | 충돌 감지 절차 | iCal 지연 등 원천 차단 불가 |

2계층에서 날짜 순서를 지키는 것이 중요하다. 스레드마다 락 획득 순서가 다르면
교착 상태가 발생하므로 `StayPeriod.nightDates()`가 오름차순을 보장한다.

## 디렉터리

```
staysync/
├── build.gradle
├── docker-compose.yml
├── docs/adr/               아키텍처 결정 기록
└── src/
    ├── main/java/com/staysync/
    │   ├── shared/         공유 커널
    │   ├── property/       숙소와 판매 단위
    │   ├── booking/        예약과 재고
    │   └── channel/port/   채널 어댑터 계약
    ├── main/resources/
    │   ├── application*.yml
    │   └── db/migration/postgresql/
    └── test/java/com/staysync/
```
