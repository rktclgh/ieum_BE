# ieum-be

이음(ieum) 백엔드. **Gradle 멀티모듈** 구조이며, 하나의 저장소에서 **서로 다른 두 대의 서버(EC2)로 각각 배포**하도록 설계되어 있습니다.

---

## 1. 왜 멀티모듈인가

기능을 물리적으로 다른 인스턴스에 나눠 올려 **자원을 격리**하기 위함입니다.

| 서버 | 담당 | 이유 |
|------|------|------|
| **EC2-1** | 핵심 REST API + 실시간 통신(WebSocket) + 실시간 알림(SSE) | 다수의 지속 커넥션을 유지 → CPU/RAM 부담 |
| **EC2-2** | AI 관련 기능 | 추론/외부 API 호출로 부하가 크고 스파이크가 있음 |

두 워크로드를 한 프로세스에 두면 한쪽의 부하 스파이크가 다른 쪽을 굶길 수 있어, **다른 jar → 다른 서버**로 분리했습니다.
단, 도메인 모델(엔티티·DTO 등)은 양쪽이 공유하므로 **2개의 저장소로 나누지 않고** `common` 모듈로 묶어 중복을 없앴습니다.

---

## 2. 모듈 구조 

```
ieum_be/
├─ settings.gradle.kts        # include("common", "app-main", "app-ai")
├─ build.gradle.kts           # 루트: 애그리게이터 (group/version/repo 공통 설정만)
│
├─ common/                    # [라이브러리] 공용 도메인 — 실행 불가(bootJar 없음)
│  └─ src/main/java/shinhan/fibri/ieum/common/
│
├─ app-main/                  # [실행모듈] EC2-1 배포 → 이미지/실행 대상
│  └─ src/main/java/shinhan/fibri/ieum/MainApplication.java
│
└─ app-ai/                    # [실행모듈] EC2-2 배포
   └─ src/main/java/shinhan/fibri/ieum/AiApplication.java
```

### 의존 방향

```
app-main ──┐
           ├──▶ common      (app-main, app-ai 가 common 을 implementation 으로 의존)
app-ai  ───┘
```

- `common` 은 어떤 실행 모듈도 의존하지 않습니다. (한 방향 의존 — 순환 금지)
- `common` 은 `java-library` 라서, 공용으로 노출할 의존성은 `api(...)` 로 선언되어 두 앱에 전이됩니다. (예: JPA, validation)

---

## 3. 각 모듈 상세

### `common`
- **역할**: 엔티티, Repository, DTO, 공용 설정/유틸.
- **패키지**: `shinhan.fibri.ieum.common`
- **빌드 산출물**: 일반 jar (실행 불가). `bootJar` 는 꺼져 있음.
- ⚠️ 두 앱이 공유하는 코드만 둡니다. 한쪽에서만 쓰는 코드는 해당 app 모듈에 두세요.

### `app-main` (EC2-1)
- **역할**: 핵심 REST API + WebSocket + SSE. 프론트가 기본으로 호출하는 서버.
- **진입점**: `shinhan.fibri.ieum.MainApplication`
- **주요 의존성**: `spring-boot-starter-webmvc`, `spring-boot-starter-websocket`, springdoc(OpenAPI)

### `app-ai` (EC2-2)
- **역할**: AI 전용. 무거운 추론/외부 AI API 연동.
- **진입점**: `shinhan.fibri.ieum.AiApplication`
- **주요 의존성**: `spring-boot-starter-webmvc` (AI 관련 의존성은 이 모듈에 추가)

> **패키지 규칙**: 두 실행 모듈의 메인 클래스는 모두 루트 패키지 `shinhan.fibri.ieum` 에 둡니다.
> 이렇게 해야 컴포넌트 스캔·엔티티 스캔·JPA Repository 스캔이 기본값으로 `common`(`...ieum.common`)까지 함께 커버합니다.
> `@EntityScan`/`@EnableJpaRepositories`/`scanBasePackages` 를 따로 지정할 필요가 없습니다.

---

## 4. 배포 매핑

각 실행 모듈은 `bootJar` 로 **자기완결적 fat jar** 를 만듭니다. `common` 은 별도로 배포되지 않고 **각 앱 jar 안에 함께 패키징**됩니다 (`BOOT-INF/lib/common-*.jar`).

```
app-main/build/libs/app-main-0.0.1-SNAPSHOT.jar   →  EC2-1   (내부에 common 포함)
app-ai/build/libs/app-ai-0.0.1-SNAPSHOT.jar       →  EC2-2   (내부에 common 포함)
```

즉 **각 서버에 올라가는 것은 jar 1개**입니다. `common` 을 서버로 따로 옮길 일은 없습니다.

- `app-main` 만 수정 → `app-main` jar 만 다시 배포
- `common` 수정 → **두 jar 모두** 다시 빌드/배포 (공유 코드가 바뀌었으므로)

> 배포 자동화(GitHub Actions / Docker)는 아직 도입하지 않았습니다. 현재는 로컬/수동 빌드 단계입니다.

---

## 5. 빌드 & 실행

### 요구사항
- JDK 21 (Gradle 툴체인이 자동 관리)
- Gradle Wrapper 사용 → 별도 설치 불필요 (`./gradlew`)

### 자주 쓰는 명령

```bash
# 전체 빌드 + 테스트
./gradlew build

# 특정 모듈 실행 (로컬 개발)
./gradlew :app-main:bootRun      # EC2-1 앱 (API/실시간)
./gradlew :app-ai:bootRun        # EC2-2 앱 (AI)

# 배포용 jar 생성
./gradlew :app-main:bootJar
./gradlew :app-ai:bootJar

# jar 직접 실행
java -jar app-main/build/libs/app-main-0.0.1-SNAPSHOT.jar

# 모듈 구조 확인
./gradlew projects
```

### 환경변수 (DB 등)
접속 정보는 코드가 아니라 환경변수로 주입합니다. (application.properties 에 값 하드코딩 금지)

```
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/ieum
SPRING_DATASOURCE_USERNAME=...
SPRING_DATASOURCE_PASSWORD=...
```

---

## 6. 새 코드는 어디에 둬야 하나 (기여 가이드)

| 만들려는 것 | 위치 |
|-------------|------|
| 엔티티 / Repository / 공용 DTO / 공용 설정 | `common` |
| REST 컨트롤러, WebSocket/SSE 핸들러, 관련 서비스 | `app-main` |
| AI 컨트롤러/서비스, AI 클라이언트 | `app-ai` |
| 한쪽 앱에서만 쓰는 유틸 | 해당 app 모듈 (공유하지 말 것) |

- **`common` 은 최소한으로**: 정말 두 앱이 함께 쓰는 것만. 애매하면 일단 app 모듈에 두고, 공유가 필요해질 때 `common` 으로 올립니다.
- 순환 의존 금지: `common` 은 절대 `app-*` 를 import 하지 않습니다.

---

## 7. ⚠️ 실시간 분리에 따른 주의점 (인스턴스 간 통신)

실시간(WebSocket/SSE)이 **EC2-1** 에만 있으므로, **EC2-2(AI)** 가 처리 결과를 유저에게 실시간으로 밀어주려면 EC2-1 에 신호를 보내야 합니다. (유저의 커넥션은 EC2-1 에 물려 있음)

```
EC2-2(AI) ──publish──▶ 메시징(Redis pub/sub 등) ──subscribe──▶ EC2-1 ──push──▶ 유저(WS/SSE)
```

AI 결과를 실시간으로 전달하는 기능을 만들 때는 이 경로(메시지 브로커)를 통해야 함을 유의하세요. (도입 시 브로커 접속 정보도 환경변수로 주입)

### 채팅 WebSocket 수신 계약

- 일반 사용자 메시지는 `/user/queue/rooms/{roomId}`로 해당 방의 active member에게 개별 전송한다. 클라이언트는 이 queue를 구독해야 한다.
- 모임 이탈 같은 system 메시지는 기존 `/topic/rooms/{roomId}` broadcast를 유지한다.
- 두 경로의 payload는 `WsMessageEvent`다. 답장 parent가 수신자의 `visibleAfterMessageId` 이전이면 메시지 자체는 전달하되 `replyTo`는 `null`이다. 클라이언트는 `replyTo=null`을 답장이 없었다는 영속 상태로 해석하면 안 된다.

---

## 8. 기술 스택

| 항목 | 버전 |
|------|------|
| Java | 21 |
| Spring Boot | 4.0.x |
| 빌드 도구 | Gradle (Kotlin DSL), Wrapper 9.5.x |
| 영속성 | Spring Data JPA |
| API 문서 | springdoc-openapi (Swagger UI) |

---

## 9. IntelliJ 에서 열 때

멀티모듈이므로 루트의 `build.gradle.kts` 를 Gradle 프로젝트로 열면 3개 모듈이 자동 인식됩니다.
구조 변경 후 모듈이 안 잡히면 **Gradle 툴윈도우 → Reload All Gradle Projects** (필요 시 File → Invalidate Caches) 로 재임포트하세요.

