package shinhan.fibri.ieum.main.file.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import shinhan.fibri.ieum.main.file.exception.FileNotFoundException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

public class S3FileStorage implements FileStorage {

	private final S3Client s3Client;
	private final S3Presigner s3Presigner;
	private final String bucket;

	public S3FileStorage(S3Client s3Client, S3Presigner s3Presigner, String bucket) {
		this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
		this.s3Presigner = Objects.requireNonNull(s3Presigner, "s3Presigner must not be null");
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalArgumentException("S3 bucket is required");
		}
		this.bucket = bucket;
	}

	@Override
	public URI createPresignedPutUrl(String key, String contentType, Long sizeBytes, Duration ttl) {
		PutObjectRequest putObjectRequest = PutObjectRequest.builder()
			.bucket(bucket)
			.key(key)
			.contentType(contentType)
			.contentLength(sizeBytes)
			.build();
		PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
			.signatureDuration(ttl)
			.putObjectRequest(putObjectRequest)
			.build();

		return URI.create(s3Presigner.presignPutObject(presignRequest).url().toString());
	}

	@Override
	public URI createPresignedGetUrl(String key, Duration ttl) {
		validateObjectKey(key);
		validateTtl(ttl);
		GetObjectRequest getObjectRequest = GetObjectRequest.builder()
			.bucket(bucket)
			.key(key)
			.build();
		GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
			.signatureDuration(ttl)
			.getObjectRequest(getObjectRequest)
			.build();

		return URI.create(s3Presigner.presignGetObject(presignRequest).url().toString());
	}

	@Override
	public FileObjectMetadata head(String key) {
		try {
			HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.build());
			return new FileObjectMetadata(response.contentType(), response.contentLength());
		} catch (NoSuchKeyException exception) {
			throw new FileNotFoundException();
		} catch (S3Exception exception) {
			throw mapNotFound(exception);
		}
	}

	@Override
	public StoredFileStream get(String key) {
		try {
			ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.build(), ResponseTransformer.toInputStream());
			return new StoredFileStream(
				key,
				response.response().contentType(),
				response.response().contentLength(),
				response
			);
		} catch (NoSuchKeyException exception) {
			throw new FileNotFoundException();
		} catch (S3Exception exception) {
			throw mapNotFound(exception);
		}
	}

	@Override
	public void put(String key, String contentType, byte[] bytes) {
		s3Client.putObject(
			PutObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.contentType(contentType)
				.contentLength((long) bytes.length)
				.build(),
			RequestBody.fromBytes(bytes)
		);
	}

	@Override
	public void delete(String key) {
		s3Client.deleteObject(DeleteObjectRequest.builder()
			.bucket(bucket)
			.key(key)
			.build());
	}

	private RuntimeException mapNotFound(S3Exception exception) {
		if (exception.statusCode() == 404) {
			return new FileNotFoundException();
		}
		return exception;
	}

	private void validateObjectKey(String key) {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("S3 object key is required");
		}
	}

	private void validateTtl(Duration ttl) {
		Objects.requireNonNull(ttl, "ttl must not be null");
		if (ttl.isZero() || ttl.isNegative()) {
			throw new IllegalArgumentException("ttl must be positive");
		}
	}
}
