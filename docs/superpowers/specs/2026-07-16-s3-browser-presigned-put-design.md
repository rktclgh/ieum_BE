# 브라우저 S3 Presigned PUT 계약 복구 설계

## 문제

프로필 수정과 모임 생성 모두 공통 3단계 업로드(`presign` → 브라우저의 S3 직접 PUT → `complete`)에서 S3 PUT 403으로 멈춘다. 앱 서버에는 PUT 요청이 도달하지 않으므로, 이 실패는 서버의 세션/CSRF 인가 오류가 아니다.

현재 presign 구현은 `PutObjectRequest.contentLength(sizeBytes)`를 서명한다. 실제 브라우저 PUT의 Content-Length는 브라우저가 전송 계층에서 관리하는 헤더이므로, 애플리케이션이 해당 값을 SigV4 서명 계약에 강제하지 않는 편이 브라우저 직접 업로드에 안전하다.

## 근본 원인 가설과 경계

첨부된 presigned URL에는 `content-length`가 signed header로 포함되어 있었다. 이 코드는 두 장애 화면의 공통 경로이며, 제거 가능한 계약 불일치다.

다만 S3 XML 오류 코드와 RequestId, 버킷 CORS/IAM/정책은 제공되지 않았다. 그러므로 이 변경은 확인된 코드 수준의 위험을 제거하지만, 운영 버킷의 CORS, signer의 `s3:PutObject` 권한, SSE/KMS 강제 헤더 조건을 대신 구성하지는 않는다.

## 결정

내부 `FileStorage`의 presigned PUT 계약에서 `sizeBytes`를 제거한다. presigner에는 `bucket`, `key`, `contentType`, `ttl`만 전달한다. API의 `sizeBytes`와 pending File DB 행은 유지한다.

업로드 완료 시 `FileService.complete()`가 S3 `HeadObject`의 실제 content type과 size를 다시 검증하고 최종 크기를 저장한다. 따라서 presign 시 Content-Length를 서명하지 않아도 파일 형식/크기 보안선은 유지된다.

## 범위

- `FileStorage`, `S3FileStorage`, `FileService`와 모든 테스트 구현체의 시그니처를 실제 사용 계약에 맞춘다.
- S3 presign 단위 테스트로 Content-Type은 유지되고 Content-Length는 서명 대상에서 빠지는지 검증한다.
- 프런트엔드의 직접 PUT 헤더 정책과 complete 단계의 검증 로직은 변경하지 않는다.

## 비범위

- S3 버킷 CORS/IAM/KMS 정책을 추측해 코드로 하드코딩하는 작업
- 업로드 API/DB 스키마 변경
- 파일 용량 검증 제거
- 임시 객체 정리 정책 변경

## 불변 조건

1. `FilePresignRequest.sizeBytes`는 pending 파일 생성 전 입력 검증에 계속 사용된다.
2. `complete()`는 실제 HeadObject 메타데이터의 타입과 크기를 계속 검증한다.
3. PUT presign URL은 요청 Content-Type을 서명하지만 Content-Length를 강제하지 않는다.
4. 실제 운영 403이 남으면 S3 XML Code/RequestId와 CORS/IAM/KMS 정책을 운영 측에서 점검해야 한다.

## 검증

- `S3FileStorageTest`에서 `PutObjectPresignRequest`를 캡처하고, 수정 전 `contentLength()`가 1024라서 실패하는 회귀 테스트를 먼저 실행한다.
- 수정 후 bucket/key/contentType/TTL과 `contentLength() == null`을 확인한다.
- `FileServiceTest`와 `S3FileStorageTest`를 함께 실행해 내부 인터페이스 변경과 완료 단계 계약이 유지되는지 확인한다.
