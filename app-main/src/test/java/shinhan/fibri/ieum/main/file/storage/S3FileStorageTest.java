package shinhan.fibri.ieum.main.file.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.file.exception.FileNotFoundException;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

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
