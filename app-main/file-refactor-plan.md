# Index

- (부모) [`spec.md`](spec.md) — 파일 파이프라인 원 설계
- 관련: [`src/main/java/shinhan/fibri/ieum/main/file/spec.md`](src/main/java/shinhan/fibri/ieum/main/file/spec.md)

# 파일/프로필 리팩토링 계획 (PR 전 보완)

## Context

Codex 1차 구현(`common`+`app-main` 파일 파이프라인, 프로필 연결/삭제)의 API 테스트는 통과했으나, 코드 리뷰에서 데이터 정합성·성능·보안 관점의 결함 4건을 발견했다. 사용자가 각 결함에 대한 수정 방향을 확정했고, 이 문서는 그 방향을 실제 구현 체크리스트로 정리한 것이다. PR 올리기 전 이 문서의 항목을 모두 처리한다.

## 발견된 문제 → 확정된 수정 방향

| # | 문제 | 위치 | 수정 방향 |
|---|---|---|---|
| 1 | `complete()`가 S3 mutation과 DB 커밋 순서가 뒤바뀌어 부분 실패 시 파일이 영구 미완료되거나 final이 고아가 됨 | `FileService.complete` | S3 작업 → DB 저장 → **커밋 성공 확인** → tmp 삭제 순서로 재배치 |
| 2 | `complete()`/`stream()`이 `@Transactional` 안에서 S3 I/O·WebP 인코딩까지 수행해 DB 커넥션을 장시간 점유 | `FileService.complete`, `FileService.stream` | 짧은 DB 트랜잭션(조회) → 트랜잭션 밖 S3/변환 → 짧은 DB 트랜잭션(확정 저장) 3단계로 분리 |
| 3 | 프로필 옛 파일 삭제가 트랜잭션 내부에서 즉시 실행되고, 다른 곳 참조 여부를 확인하지 않음 | `UserService.deleteProfileFile` | DB 커밋 확정 후(afterCommit) + 참조 여부 확인 후 인라인 삭제 |
| 5 | `GET /files/{fileId}` 스트리밍이 전체 바이트를 `byte[]`로 메모리에 로딩 | `S3FileStorage.get`, `FileService.stream`, `FileController.stream` | 실제 `InputStream` 기반 스트리밍으로 전환 |

> 번호는 원 코드 리뷰의 이슈 번호를 그대로 사용(#4 pending row GC, #6 null content-type, #7 픽셀 상한, #8 동시 complete 레이스는 후속 과제로 별도 섹션에 남김. #9는 이번 리팩토링에 곁다리로 포함).

---

## 1. `complete()` 순서 수정

**현재**(`FileService.java:70-89`): `copy → 렌더링 put → save(파일 확정) → delete(tmp)`가 한 트랜잭션 안에 있어, 렌더링 실패 시 final이 고아로 남고 커밋 실패 시 tmp가 먼저 지워져 영구 미완료가 될 수 있음.

**변경**: S3 mutation(모두 멱등) → DB 저장 → **트랜잭션 커밋 성공** → tmp 삭제. `s3_key`가 커밋 전까지 tmp를 계속 가리키므로, 커밋 실패 시 재시도만으로 자연 복구된다(재시도가 같은 tmp 키로 HEAD/copy를 다시 시도, 결과는 덮어쓰기라 안전).

- `promoteKey`/`markUploaded` 저장까지가 트랜잭션의 끝.
- `storage.delete(tmpKey)`는 **트랜잭션 커밋 이후에만** 실행(2번 항목의 3단계 분리와 함께 처리 — 아래 참조).
- 커밋 성공 후 delete 직전에 죽는 극단 케이스는 코드로 안 막고, 기존 설계의 **tmp 1일 S3 Lifecycle**이 백스톱으로 정리한다. → **인프라 확인 필요**: 버킷에 tmp/ prefix 만료 규칙이 실제로 걸려 있는지 점검(코드 밖 작업, 체크리스트에 포함).

## 2. 짧은 트랜잭션 3단계 분리

**대상**: `FileService.complete`, `FileService.stream` 둘 다.

**패턴**:
```
[짧은 @Transactional] 파일 row 조회 + 소유권/상태 검증 → 필요한 값만 꺼내서 반환
[트랜잭션 없음]        S3 HEAD/copy/get, WebP 렌더링, S3 put
[짧은 @Transactional] 확정 필드 저장(complete) 또는 없음(stream은 조회만으로 충분)
```

**⚠️ Spring self-invocation 주의**: `FileService`가 하나의 빈이고 그 안에서 `@Transactional` 메서드를 `this.xxx()`로 호출하면 프록시를 안 거쳐 **트랜잭션이 아예 시작되지 않는다.** 아래 중 하나로 해결:
- DB 전용 읽기/쓰기 메서드를 **별도의 작은 협력 빈**(예: `FileTransactionalOps`)으로 분리해 `FileService`가 주입받아 호출.
- 또는 `FileService`를 `@Lazy` 자기 자신 주입으로 프록시 경유 호출.

→ **권장**: 별도 협력 빈 분리(테스트도 더 쉬움). 예시 구조:
```java
@Service
class FileTransactionalOps {
    @Transactional(readOnly = true)
    File loadOwned(UUID fileId, Long userId) { ... }   // complete용

    @Transactional(readOnly = true)
    File loadUploaded(UUID fileId) { ... }              // stream용

    @Transactional
    void finalizeUpload(UUID fileId, String finalKey, OffsetDateTime uploadedAt, String contentType, Long sizeBytes) { ... }
}
```
`FileService.complete`/`stream`은 이 빈을 호출하는 오케스트레이터로 남고, 자기 자신은 `@Transactional`을 갖지 않는다.

**`complete()` 흐름 재작성**:
1. `ops.loadOwned(fileId, userId)` — 짧은 트랜잭션, File 반환(또는 필요한 필드만 담은 record).
2. 이미 업로드됐으면 바로 반환(기존 멱등 로직 유지).
3. 트랜잭션 밖: HEAD → validate → copy(tmp→final) → 렌더링 → put(display/thumb).
4. `ops.finalizeUpload(...)` — 짧은 트랜잭션, `promoteKey`+`markUploaded`+save.
5. 커밋 성공 확인 후(메서드 정상 반환) `storage.delete(tmpKey)`.

**`stream()` 흐름 재작성**:
1. `ops.loadUploaded(fileId)` — 짧은 트랜잭션으로 `s3_key`만 확보하고 즉시 반환.
2. 트랜잭션 밖에서 S3 GET(스트리밍, 4번 항목과 연결) 수행.

## 3. 프로필 옛 파일 삭제 — 커밋 확정 후 + 참조 확인 + 인라인 삭제

**현재**(`UserService.java:171-178`): `deleteProfileFile`이 `updateProfileImage`/`deleteProfileImage`의 같은 트랜잭션 안에서 즉시 S3 3개 + DB row를 삭제. 커밋 실패 시 롤백된 `profileFileId`가 가리키는 옛 파일이 이미 지워져 있어 프로필이 깨짐. 참조 확인도 없음.

**변경**:
- **커밋 확정 후 삭제**: 기존 `withdraw()`에 이미 있는 패턴을 재사용.
  ```java
  // UserService.withdraw() 참고 (194-230줄 부근)
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
          deleteProfileFileSafely(previousFileId);
      }
  });
  ```
  `updateProfileImage`/`deleteProfileImage` 둘 다 이 패턴으로 옛 파일 삭제를 afterCommit으로 옮긴다.
- **참조 확인**: 프로필 슬라이스에서 `files.file_id`를 참조할 수 있는 곳은 현재 `users.profile_file_id`뿐이므로, 삭제 직전에 `UserRepository.existsByProfileFileId(UUID fileId)`(신규 메서드, `common`) 결과가 `false`인지 한 번 더 확인 후 삭제. 이미 우리가 값을 바꾼 뒤라 이론상 false지만, 동시 요청 레이스에 대한 벨트-앤-서스펜더.
- **삭제 실패는 응답에 영향 없게**: `withdraw()`의 `revokeSessionsLogOnly`처럼 try/catch로 감싸 로그만 남기고 삼킨다(`deleteProfileFileSafely`).

**신규**: `common/src/main/java/shinhan/fibri/ieum/common/auth/repository/UserRepository.java`에 `boolean existsByProfileFileId(UUID profileFileId)` 추가.

## 4. 실제 스트리밍

**현재**: `S3FileStorage.get()` → `getObjectAsBytes()` → `StoredFileObject(byte[])` → `FileStreamResponse(byte[])` → `ResponseEntity<byte[]>`. 전체 바이트를 매 요청 힙에 올림.

**변경**:
- `FileStorage.get(String key)`의 반환을 `byte[]` 보유 record 대신 **스트림 기반**으로 교체. 예: `StoredFileStream(String contentType, Long sizeBytes, InputStream body)` (AWS SDK `ResponseInputStream<GetObjectResponse>`를 그대로 `InputStream`으로 노출).
- `S3FileStorage.get`: `s3Client.getObject(GetObjectRequest, ResponseTransformer.toInputStream())` 사용, `contentType`/`contentLength`는 응답 메타데이터에서 스트림 열기 시점에 확보(별도 HEAD 불필요).
- `FileController.stream`: `ResponseEntity<byte[]>` → `ResponseEntity<StreamingResponseBody>`로 변경. `Content-Type`/`Content-Length`/`Cache-Control`/`nosniff` 헤더는 그대로 유지하고, body는 `StreamingResponseBody`가 S3 `InputStream`을 HTTP 응답에 try-with-resources로 복사.
- 2번 항목(짧은 트랜잭션)과 자연히 맞물림: DB 조회(짧은 트랜잭션)로 `s3_key`만 확보하고, **트랜잭션이 끝난 뒤** 스트림을 열어 응답에 흘린다 — 스트리밍 내내 DB 커넥션을 잡지 않음.

## 곁다리 (2번 리팩토링 중 같이 처리하면 이득)

`complete()`가 `copy(tmp→final)` 직후 `storage.get(finalKey)`로 원본을 **다시 다운로드**해서 렌더링 중(`FileService.java:77-78`). tmp에서 이미 받은 바이트를 그대로 재사용하면 S3 GET 왕복 1회를 절약하고, "copy 성공 + 렌더링 실패" 시 재조회 실패 가능성도 줄어든다. 순서 재작성(1번 항목) 기회에 같이 정리:
```
head(tmpKey) → get(tmpKey) [원본 바이트 1회 확보] → 렌더링(로컬 바이트로) → copy(tmp→final) 또는 put(finalOriginKey, 원본바이트) + put(display) + put(thumb)
```
(copy 대신 원본 바이트를 final에 직접 put해도 됨 — 이러면 S3 서버사이드 copy 자체도 생략 가능. 택1)

---

## 파일별 변경 목록

**신규**
- `app-main/.../main/file/service/FileTransactionalOps.java` (또는 유사 협력 빈) — 짧은 DB 트랜잭션 전담
- `app-main/.../main/file/storage/StoredFileStream.java` (byte[] record 대체)

**수정**
- `common/.../auth/repository/UserRepository.java` — `existsByProfileFileId` 추가
- `app-main/.../main/file/service/FileService.java` — `complete`/`stream` 오케스트레이션 재작성(트랜잭션 제거, ops 위임)
- `app-main/.../main/file/storage/FileStorage.java` — `get` 반환 타입 스트림으로 변경
- `app-main/.../main/file/storage/S3FileStorage.java` — `get` 구현 스트림 기반으로 교체
- `app-main/.../main/file/controller/FileController.java` — `stream` 응답 `StreamingResponseBody`로 교체
- `app-main/.../main/file/dto/FileStreamResponse.java` — `byte[]` → 스트림 기반으로 조정(또는 컨트롤러에서 직접 storage 결과 사용)
- `app-main/.../main/user/service/UserService.java` — `deleteProfileFile` → afterCommit + 참조 확인 + try/catch 삭제로 교체

**삭제**
- 없음(구조 변경, 파일 제거 없음)

## 테스트 업데이트

- `FileServiceTest`: 짧은 트랜잭션 분리 후에도 presign/complete 멱등·소유권·렌더링 생성 검증 유지. `FileTransactionalOps`는 fake/mock으로 대체하거나 `@SpringBootTest` 슬라이스로 실제 트랜잭션 경계 검증.
- 신규: complete 중 렌더링 실패 시 DB가 여전히 pending인지(고아 없음) 검증하는 테스트.
- 신규: `UserServiceTest`에 옛 프로필 파일이 **커밋 후에만** 삭제되는지(트랜잭션 롤백 시 삭제 호출 안 됨) 검증하는 테스트.
- `FileControllerTest`: 스트리밍 응답이 `StreamingResponseBody`로 오는지, 헤더(`Content-Type`/`Content-Length`/`Cache-Control`/`nosniff`)가 유지되는지 검증.
- `:common:test`, `:app-main:test` 전체 통과 확인.

## 체크리스트

- [x] 1. `complete()` 순서: S3 작업 → DB 저장 → 커밋 확인 → tmp 삭제
- [x] 2-a. `FileTransactionalOps`(또는 동등 협력 빈) 분리, self-invocation 회피
- [x] 2-b. `complete()`를 3단계(짧은 tx → S3/변환 → 짧은 tx)로 재작성
- [x] 2-c. `stream()`을 짧은 tx + tx 밖 S3 GET으로 재작성
- [x] 3-a. `UserRepository.existsByProfileFileId` 추가
- [x] 3-b. 옛 프로필 파일 삭제를 afterCommit + 참조 확인 + try/catch로 교체
- [x] 4-a. `FileStorage.get`/`S3FileStorage.get` 스트림 기반으로 교체
- [x] 4-b. `FileController.stream`을 `StreamingResponseBody`로 교체
- [x] (곁다리) complete의 중복 GET 제거(tmp 바이트 재사용)
- [ ] 인프라: tmp/ prefix S3 Lifecycle 1일 만료 규칙 실제 적용 확인
- [x] `:common:test`, `:app-main:test` 통과
- [x] `memory.md` 트리에 완료 기록

## 후속 과제 (이번 PR 범위 밖, 트래킹만)

- pending row(`uploaded_at IS NULL`) DB 청소 잡 — complete 안 부른 이탈 건
- 렌더링 입력 픽셀 상한(OOM 방지)
- 동시 `complete()` 레이스로 인한 중복 S3 작업(손상 없음, 낭비만 — 필요 시 락 고려)

---

# PR 전 추가 수정 (2차 리뷰에서 발견 — 2026-07-07)

1차 리팩토링(위 4개 항목)은 문서대로 정확히 반영됨을 코드로 확인했다(`FileTransactionalOps` 분리, complete 순서, afterCommit 삭제, `StreamingResponseBody`). 다만 리팩토링 과정에서 **새로 생긴 리소스 누수 1건**과 **원래 있던 상태코드 버그 1건**을 발견했다. 아래 🔴 2건은 PR 전 필수 수정.

## 🔴 A. S3 스트림 커넥션 누수 (스트리밍 전환 회귀 — 최우선)

**문제**: S3 `InputStream`(=HTTP 커넥션)이 `FileService.stream`에서 열리는데, 닫는 책임은 `FileController`의 `StreamingResponseBody` 람다에 있다. 스트림을 연 뒤~람다 실행 전 사이에 예외가 나면 스트림이 영영 안 닫혀 **S3 클라이언트 커넥션 풀이 고갈**된다(서서히 앱 hang).

던지는 지점 2곳:
- `FileService.stream` ([FileService.java:104-105](src/main/java/shinhan/fibri/ieum/main/file/service/FileService.java)): `storage.get(key)`로 스트림을 연 직후 `validateStreamContentType(...)`가 throw하면 `object.body()`가 안 닫힘.
- `FileController.stream` ([FileController.java:62](src/main/java/shinhan/fibri/ieum/main/file/controller/FileController.java)): `MediaType.parseMediaType(response.contentType())`가 throw하면 `body(body)` 람다(스트림 닫는 유일한 곳)가 아예 실행 안 됨.

**트리거**: S3 객체 content-type이 null/이상값이면 매 요청 커넥션 1개씩 누수 → 수백 요청 후 고갈.

**수정 방향(택1, 권장 순)**:
1. **S3 GET을 `StreamingResponseBody` 람다 안에서 열기**. 서비스는 `s3Key` + variant + (미리 확보한) content-type/length 메타만 반환하고, 실제 `storage.get`은 람다 안에서 열고 try-with-resources로 닫는다. content-type/length는 GET 스트림 대신 **`head`로 먼저** 확보(검증도 head 결과로 수행) → 헤더 조립이 스트림 오픈보다 앞서므로 parseMediaType가 던져도 누수 없음.
2. 최소 방어: `FileService.stream`에서 `storage.get` 후 검증을 try/catch로 감싸 실패 시 `object.body().close()`. + 컨트롤러 `parseMediaType`을 스트림 오픈 전에 계산.

→ **1안 권장**. 부수 효과로 null content-type(아래 C)도 head 단계에서 함께 방어 가능.

## 🔴 B. 파일 API 클라이언트 에러가 전부 HTTP 500

**문제**: `InvalidFileRequestException`·`FileNotFoundException`에 매핑 핸들러가 없다. 둘 다 `@ResponseStatus` 없는 `RuntimeException`이라 `GlobalExceptionHandler`의 `Exception.class` 폴백([GlobalExceptionHandler.java:53-58](../config/GlobalExceptionHandler.java) 위치는 `shinhan.fibri.ieum.config`)에 걸려 **전부 500**으로 나간다.

- `GET /files/{id}?v=foo` → 500 (400이어야)
- `GET /files/{id}` 없는/미완료 파일 → 500 (404여야)
- presign 잘못된 contentType/size → 500 (400이어야)
- complete on 남의/없는 파일 → 500 (404여야)

`FileControllerTest`는 `isOk()`만 검증(에러 경로 테스트 0개)이라 안 잡혔다.

**수정 방향**:
- `main/file/controller/FileExceptionHandler.java`(`@RestControllerAdvice`) 신규:
  - `InvalidFileRequestException` → `400 BAD_REQUEST`
  - `FileNotFoundException` → `404 NOT_FOUND`
  - (선택) `UncheckedIOException` 등 스토리지/렌더 실패 → `502 BAD_GATEWAY` 또는 `500` 명시
- 에러 응답 바디는 기존 `AuthErrorResponse` 포맷과 일관되게.
- **에러 경로 테스트 추가**: 잘못된 variant→400, 없는 파일→404, presign 검증 실패→400.

## 🟡 C. null content-type 방어

`head`/`get`의 content-type이 null이면 `FileObjectKeys.normalize`가 던짐 → (A와 겹쳐) 누수+500. origin은 presign에서 content-type을 서명하므로 대체로 채워지지만, 방어적으로 기본값(`application/octet-stream`) 처리하거나 A의 1안(head 선검증)으로 흡수.

## 🟡 D. `purpose` 값 화이트리스트

현재 `FileObjectKeys.cleanPurpose`는 형식 검증(`[a-z][a-z0-9_-]{0,39}`)만이라 `purpose=banana`도 통과해 임의 폴더 생성 가능(경로 traversal은 아님 — `/`·`.` 차단됨). 알려진 목적 집합(profile/meeting/chat/question/answer)으로 좁힐지 결정.

## 🟡 E. `FileStorage.copy` 死코드

complete가 copy 대신 `put(finalKey, origin.bytes())`를 쓰므로 `copy`는 미사용. 인터페이스/구현에서 정리 가능(당장 해롭진 않음).

## 추가 체크리스트

- [x] A. 스트림 누수 제거(GET을 람다 안에서 열거나, 검증 실패 시 close + parseMediaType 선계산)
- [x] B. `FileExceptionHandler` 추가(400/404 매핑) + 에러 경로 테스트
- [x] C. null content-type 방어(또는 A의 head 선검증으로 흡수)
- [x] D. `purpose` 값 화이트리스트 결정/적용
- [x] E. `FileStorage.copy` 死코드 정리
- [x] `:app-main:test` 재통과(에러 경로 테스트 포함)
