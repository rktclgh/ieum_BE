package shinhan.fibri.ieum.main.file.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import shinhan.fibri.ieum.main.file.exception.FileNotFoundException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

class S3FileStorageTest {

	private final S3Client s3Client = mock(S3Client.class);
	private final S3Presigner s3Presigner = mock(S3Presigner.class);

	@Test
	void constructorRejectsBlankBucket() {
		assertThatThrownBy(() -> new S3FileStorage(s3Client, s3Presigner, " "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("S3 bucket is required");
	}

	@Test
	void createPresignedGetUrlPresignsGetObjectWithRequestedTtl() {
		S3FileStorage storage = new S3FileStorage(s3Client, s3Presigner, "bucket");
		PresignedGetObjectRequest presignedRequest = PresignedGetObjectRequest.builder()
			.expiration(Instant.parse("2026-07-14T00:05:00Z"))
			.isBrowserExecutable(true)
			.signedHeaders(Map.of("host", List.of("storage.example")))
			.httpRequest(SdkHttpFullRequest.builder()
				.method(SdkHttpMethod.GET)
				.uri(URI.create("https://storage.example/final/image.jpg?X-Amz-Signature=test"))
				.build())
			.build();
		when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedRequest);

		URI url = storage.createPresignedGetUrl("final/image.jpg", Duration.ofMinutes(5));

		ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
		org.mockito.Mockito.verify(s3Presigner).presignGetObject(captor.capture());
		GetObjectPresignRequest request = captor.getValue();
		assertThat(url).isEqualTo(URI.create("https://storage.example/final/image.jpg?X-Amz-Signature=test"));
		assertThat(request.signatureDuration()).isEqualTo(Duration.ofMinutes(5));
		assertThat(request.getObjectRequest().bucket()).isEqualTo("bucket");
		assertThat(request.getObjectRequest().key()).isEqualTo("final/image.jpg");
	}

	@Nested
	class CreatePresignedGetUrlValidation {

		private final S3FileStorage storage = new S3FileStorage(s3Client, s3Presigner, "bucket");

		@Test
		void rejectsBlankKey() {
			assertThatThrownBy(() -> storage.createPresignedGetUrl(" ", Duration.ofMinutes(5)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("S3 object key is required");
		}

		@Test
		void rejectsNullTtl() {
			assertThatThrownBy(() -> storage.createPresignedGetUrl("final/image.jpg", null))
				.isInstanceOf(NullPointerException.class)
				.hasMessage("ttl must not be null");
		}

		@Test
		void rejectsNonPositiveTtl() {
			assertThatThrownBy(() -> storage.createPresignedGetUrl("final/image.jpg", Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("ttl must be positive");
		}
	}

	@Test
	void headConvertsNoSuchKeyToFileNotFound() {
		S3FileStorage storage = new S3FileStorage(s3Client, s3Presigner, "bucket");
		when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

		assertThatThrownBy(() -> storage.head("missing"))
			.isInstanceOf(FileNotFoundException.class);
	}

	@Test
	void headConvertsS3NotFoundStatusToFileNotFound() {
		S3FileStorage storage = new S3FileStorage(s3Client, s3Presigner, "bucket");
		when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());

		assertThatThrownBy(() -> storage.head("missing"))
			.isInstanceOf(FileNotFoundException.class);
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void getConvertsNoSuchKeyToFileNotFound() {
		S3FileStorage storage = new S3FileStorage(s3Client, s3Presigner, "bucket");
		when(s3Client.getObject(any(GetObjectRequest.class), any(ResponseTransformer.class)))
			.thenThrow(NoSuchKeyException.builder().build());

		assertThatThrownBy(() -> storage.get("missing"))
			.isInstanceOf(FileNotFoundException.class);
	}
}
