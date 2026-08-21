# 개발 환경 준비 — Docker Desktop 설치 안내 (Windows)

프로젝트: StaySync
작성일: 2026년 8월 21일
대상: Windows 10 (21H2 이상) 또는 Windows 11

---

## 1. 지금 당장 설치하지 않아도 된다

프로젝트는 실행 프로파일 두 개로 구성한다.

| 프로파일 | 구성 | Docker 필요 | 용도 |
|---|---|---|---|
| `local` | H2 (PostgreSQL 호환 모드) + JVM 내부 락 + 메모리 벡터 검색 | 없음 | 일상 개발, 화면 작업, 도메인 로직 |
| `docker` | PostgreSQL 16 + pgvector + Redis | 필요 | 통합 테스트, 최종 검증, 배포 |

`local` 프로파일만으로 앱을 띄우고 대부분의 기능을 개발할 수 있다. 아래 표가 그 경계다.

| 항목 | `local` (H2) | 비고 |
|---|---|---|
| 숙소·객실·요금제 CRUD | 가능 | |
| 예약 생성·수정·취소 | 가능 | |
| 통합 캘린더 | 가능 | |
| 요금 일괄 편집, 요금 규칙 | 가능 | |
| iCal 수신·발행 | 가능 | 외부 HTTP만 쓴다 |
| Mock OTA 시뮬레이터 | 가능 | 별도 포트로 같이 띄운다 |
| 재고 락 (`SELECT FOR UPDATE`) | 가능 | H2도 지원하나 동작이 PostgreSQL과 완전히 같지는 않다 |
| AI 게스트 응대 (RAG) | 가능 | 청크 수백 개 규모라 메모리에서 코사인 유사도를 직접 계산해도 충분하다 |
| **동시성 100스레드 테스트** | **불가** | Testcontainers가 Docker를 요구한다 |
| **`FOR UPDATE SKIP LOCKED` 큐** | **불가** | H2가 지원하지 않는다. `local`에서는 단순 락으로 대체 동작한다 |
| **pgvector HNSW 인덱스** | **불가** | 성능 검증은 PostgreSQL에서만 가능하다 |

즉 **P1의 완료 조건인 "재고 1개에 동시 100요청 → 1건만 성공" 테스트는 Docker 없이 돌릴 수 없다.** 이 테스트는 계획서 18.3절 평가 기준에서 "기술적 난제 해결" 20점의 근거가 되는 항목이므로, P1이 끝나는 9월 말 이전에는 Docker가 준비되어야 한다.

정리하면, **지금 시작하는 데는 지장이 없고 9월 안에 설치하면 된다.**

---

## 2. 설치 전 확인 사항

### 2.1 시스템 요구사항

| 항목 | 요구 사양 |
|---|---|
| 운영체제 | Windows 10 64비트 21H2 이상, 또는 Windows 11 (Home/Pro/Enterprise/Education 모두 가능) |
| CPU | 64비트, SLAT 지원 |
| 가상화 | BIOS에서 Intel VT-x 또는 AMD-V 활성화 |
| 메모리 | 최소 4GB, 권장 8GB 이상 |
| 디스크 | 약 6GB 여유 공간 |
| 백엔드 | WSL 2 권장 (Hyper-V도 가능) |

### 2.2 내 PC가 조건을 만족하는지 확인

**Windows 버전 확인**

`Win + R` → `winver` 입력 → 확인. "버전 21H2" 이상이면 된다.

**가상화 활성화 여부 확인**

작업 관리자(`Ctrl + Shift + Esc`) → 성능 탭 → CPU 선택 → 우측 하단 "가상화" 항목이 **사용**으로 표시되면 정상이다.

"사용 안 함"이면 BIOS 설정이 필요하다. 재부팅 시 제조사별 키(주로 `F2`, `Del`, `F10`)로 진입해 Advanced 또는 CPU Configuration 항목에서 다음을 켠다.

- Intel CPU: `Intel Virtualization Technology` 또는 `VT-x`
- AMD CPU: `SVM Mode`

---

## 3. 설치 절차

### 3.1 WSL 2 설치

PowerShell을 **관리자 권한으로** 실행한 뒤 다음을 입력한다.

```powershell
wsl --install
```

이 명령 하나가 필요한 Windows 기능(가상 머신 플랫폼, WSL)을 켜고 기본 리눅스 배포판까지 설치한다. 완료 후 재부팅한다.

이미 WSL이 있다면 버전만 올린다.

```powershell
wsl --set-default-version 2
wsl --update
```

설치 결과 확인:

```powershell
wsl -l -v
```

`VERSION` 열이 `2`로 나오면 된다.

### 3.2 Docker Desktop 설치

1. [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/) 에서 Windows용 설치 파일을 받는다. CPU에 맞는 버전을 고른다 (일반 PC는 AMD64, ARM 기반 노트북은 ARM64).
2. 설치 파일을 실행하고 **"Use WSL 2 instead of Hyper-V"** 옵션에 체크된 상태로 진행한다.
3. 설치가 끝나면 재부팅한다.
4. Docker Desktop을 실행한다. 첫 실행 때 계정 로그인을 권하는데, 건너뛰어도 로컬 사용에는 지장이 없다.

### 3.3 설치 확인

PowerShell 또는 터미널에서:

```powershell
docker --version
docker compose version
docker run --rm hello-world
```

세 번째 명령이 "Hello from Docker!" 메시지를 출력하면 정상이다.

---

## 4. 이 프로젝트에서의 사용법

프로젝트 루트에 `docker-compose.yml`이 들어간다. 데이터베이스와 캐시만 컨테이너로 띄우고, 애플리케이션은 IDE에서 직접 실행하는 방식이 개발 중에는 편하다.

```powershell
# 데이터베이스와 캐시만 기동
docker compose up -d postgres redis

# 상태 확인
docker compose ps

# 로그 보기
docker compose logs -f postgres

# 종료 (데이터는 보존)
docker compose stop

# 종료하고 데이터까지 삭제
docker compose down -v
```

애플리케이션 실행 시 프로파일을 지정한다.

```powershell
# Docker 없이
./gradlew bootRun --args='--spring.profiles.active=local'

# Docker 사용
./gradlew bootRun --args='--spring.profiles.active=docker'
```

---

## 5. 자주 겪는 문제

| 증상 | 원인과 해결 |
|---|---|
| `WSL 2 installation is incomplete` | `wsl --update` 실행 후 재부팅 |
| Docker Desktop이 시작되다 멈춤 | 가상화가 BIOS에서 꺼져 있다. 2.2절 확인 |
| `docker` 명령을 찾을 수 없음 | Docker Desktop이 실행 중이어야 한다. 터미널을 새로 열어본다 |
| 메모리를 과도하게 점유 | 사용자 폴더에 `.wslconfig` 파일을 만들어 제한한다 (아래) |
| 포트 5432가 이미 사용 중 | PostgreSQL이 로컬에 설치되어 있다. `docker-compose.yml`에서 `5433:5432`로 바꾼다 |

메모리 제한 설정 — `C:\Users\User\.wslconfig` 파일 생성:

```ini
[wsl2]
memory=4GB
processors=2
```

---

## 6. 라이선스 참고

Docker Desktop은 개인 사용, 교육 목적, 소규모 사업자에게 무료다(Docker Personal). 대규모 상업적 사용에는 유료 구독이 필요하므로, 학생 프로젝트는 무료 범위에 해당한다. 다만 약관은 바뀔 수 있으니 설치 시점에 [Docker 구독 약관](https://www.docker.com/legal/docker-subscription-service-agreement/)을 한 번 확인해 두면 좋다.

라이선스가 부담스럽거나 Docker Desktop이 무겁게 느껴지면 대안이 있다.

- **Podman Desktop** — Apache 2.0 라이선스, Docker와 명령 호환. `docker` 대신 `podman` 사용
- **Rancher Desktop** — Apache 2.0 라이선스, WSL 2 기반

두 대안 모두 Testcontainers와 연동되지만 설정을 한 단계 더 거쳐야 한다. 처음이라면 Docker Desktop이 무난하다.

---

## 참고

- [Docker Desktop 시스템 요구사항 (2026)](https://usedocker.com/system-requirements)
- [Windows용 Docker Desktop 설치 가이드](https://usedocker.com/install/windows)
- [Docker Desktop 공식 다운로드](https://www.docker.com/products/docker-desktop/)
