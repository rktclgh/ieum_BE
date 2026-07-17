# 브라우저 S3 Presigned PUT 계약 복구 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 브라우저 직접 S3 PUT의 Content-Length를 SigV4 서명에서 제외하면서 실제 업로드 객체의 완료 단계 검증을 유지한다.

**Architecture:** 내부 storage presign 인터페이스는 key/contentType/ttl만 받는다. API 입력의 sizeBytes는 pending 파일 저장과 complete 단계의 HeadObject 검증에 남겨, 브라우저 전송 헤더에 의존하지 않는 보안 경계를 유지한다.

**Tech Stack:** Java 21, AWS SDK v2 S3 Presigner, JUnit 5, Mockito, Gradle.

## Global Constraints

- 공개 API `FilePresignRequest.sizeBytes`와 File pending DB 저장을 유지한다.
- `FileService.complete()`의 S3 HeadObject 타입/크기 검증을 제거하거나 완화하지 않는다.
- presigned PUT은 Content-Type을 유지하되 Content-Length를 서명하지 않는다.
- S3 CORS/IAM/KMS 운영 설정을 추측해 애플리케이션 코드로 추가하지 않는다.
- 테스트를 먼저 작성하고 수정 전 Content-Length assertion 실패를 확인한다.

---

### Task 1: 브라우저 호환 PUT presign 계약

**Files:**
- Modify: `app-main/src/test/java/shinhan/fibri/ieum/main/file/storage/S3FileStorageTest.java`
- Modify: `app-main/src/main/java/shinhan/fibri/ieum/main/file/storage/FileStorage.java`
- Modify: `app-main/src/main/java/shinhan/fibri/ieum/main/file/storage/S3FileStorage.java`
- Modify: `app-main/src/main/java/shinhan/fibri/ieum/main/file/service/FileService.java`
- Modify: `app-main/src/test/java/shinhan/fibri/ieum/main/file/service/FileServiceTest.java`
- Modify: `app-main/src/test/java/shinhan/fibri/ieum/main/report/ai/service/ReportAiPipelineIntegrationTest.java`
- Create: `docs/superpowers/specs/2026-07-16-s3-browser-presigned-put-design.md`
- Create: `docs/superpowers/plans/2026-07-16-s3-browser-presigned-put.md`

**Interfaces:**
- Consumes: `FilePresignRequest.sizeBytes` for pending-file validation and `FileStorage.createPresignedPutUrl(key, contentType, ttl)` for S3 URL creation.
- Produces: a PUT URL whose S3 request signs bucket, key, Content-Type, and TTL but leaves Content-Length unset.

- [ ] **Step 1: Write the failing S3 presign regression test**

Add imports for `PutObjectPresignRequest` and `PresignedPutObjectRequest`, then add this test to `S3FileStorageTest` using the existing mock `s3Presigner`:

```java
@Test
void createPresignedPutUrlSignsContentTypeButNotContentLength() {
	S3FileStorage storage = new S3FileStorage(s3Client, s3Presigner, "bucket");
	PresignedPutObjectRequest presignedRequest = PresignedPutObjectRequest.builder()
		.expiration(Instant.parse("2026-07-16T00:05:00Z"))
		.isBrowserExecutable(true)
		.signedHeaders(Map.of("host", List.of("storage.example")))
		.httpRequest(SdkHttpFullRequest.builder()
			.method(SdkHttpMethod.PUT)
			.uri(URI.create("https://storage.example/tmp/original.jpg?X-Amz-Signature=test"))
			.build())
		.build();
	when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedRequest);

	URI url = storage.createPresignedPutUrl(
		"tmp/original.jpg",
		"image/jpeg",
		1024L,
		Duration.ofMinutes(5)
	);

	ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
	org.mockito.Mockito.verify(s3Presigner).presignPutObject(captor.capture());
	PutObjectPresignRequest request = captor.getValue();
	assertThat(url).isEqualTo(URI.create("https://storage.example/tmp/original.jpg?X-Amz-Signature=test"));
	assertThat(request.signatureDuration()).isEqualTo(Duration.ofMinutes(5));
	assertThat(request.putObjectRequest().bucket()).isEqualTo("bucket");
	assertThat(request.putObjectRequest().key()).isEqualTo("tmp/original.jpg");
	assertThat(request.putObjectRequest().contentType()).isEqualTo("image/jpeg");
	assertThat(request.putObjectRequest().contentLength()).isNull();
}
```

- [ ] **Step 2: Run the test to verify it fails before the fix**

Run:

```bash
./gradlew :app-main:test --tests shinhan.fibri.ieum.main.file.storage.S3FileStorageTest.createPresignedPutUrlSignsContentTypeButNotContentLength
```

Expected: FAIL because the captured request currently has `contentLength() == 1024L`.

- [ ] **Step 3: Remove Content-Length from the internal presign contract**

Apply exactly these interface and call-site changes:

```java
// FileStorage.java
URI createPresignedPutUrl(String key, String contentType, Duration ttl);

// FileService.java
URI uploadUrl = storage.createPresignedPutUrl(file.getS3Key(), contentType, properties.presignTtl());

// S3FileStorage.java
public URI createPresignedPutUrl(String key, String contentType, Duration ttl) {
	PutObjectRequest putObjectRequest = PutObjectRequest.builder()
		.bucket(bucket)
		.key(key)
		.contentType(contentType)
		.build();
	PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
		.signatureDuration(ttl)
		.putObjectRequest(putObjectRequest)
		.build();

	return URI.create(s3Presigner.presignPutObject(presignRequest).url().toString());
}
```

Replace the fake storage signatures exactly as follows while preserving their existing bodies:

```java
// FileServiceTest.java
public URI createPresignedPutUrl(String key, String contentType, Duration ttl)

// ReportAiPipelineIntegrationTest.java
public URI createPresignedPutUrl(String key, String contentType, Duration ttl)
```

Update the regression test invocation to remove `1024L` after the interface change; keep its `contentLength().isNull()` assertion. Do not change FileService's `validateSizeBytes`, `File.pending(... sizeBytes)`, or `complete()`.

- [ ] **Step 4: Run the focused storage and service regression suite**

Run:

```bash
./gradlew :app-main:test --tests shinhan.fibri.ieum.main.file.storage.S3FileStorageTest --tests shinhan.fibri.ieum.main.file.service.FileServiceTest
```

Expected: PASS. The Gradle test compilation also verifies the reporting integration test's fake storage implements the new internal contract.

- [ ] **Step 5: Commit the scoped change**

```bash
git add docs/superpowers/specs/2026-07-16-s3-browser-presigned-put-design.md docs/superpowers/plans/2026-07-16-s3-browser-presigned-put.md app-main/src/main/java/shinhan/fibri/ieum/main/file/storage/FileStorage.java app-main/src/main/java/shinhan/fibri/ieum/main/file/storage/S3FileStorage.java app-main/src/main/java/shinhan/fibri/ieum/main/file/service/FileService.java app-main/src/test/java/shinhan/fibri/ieum/main/file/storage/S3FileStorageTest.java app-main/src/test/java/shinhan/fibri/ieum/main/file/service/FileServiceTest.java app-main/src/test/java/shinhan/fibri/ieum/main/report/ai/service/ReportAiPipelineIntegrationTest.java
git commit -m "fix: make S3 put presign browser compatible"
```
