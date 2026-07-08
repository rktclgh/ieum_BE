package shinhan.fibri.ieum.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.main.file.service.FileProperties;
import shinhan.fibri.ieum.main.file.storage.FileStorage;
import shinhan.fibri.ieum.main.file.storage.S3FileStorage;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class FileConfig {

	@Bean
	FileProperties fileProperties(
		@Value("${app.file.s3.tmp-prefix:${APP_FILE_S3_TMP_PREFIX:tmp}}") String tmpPrefix,
		@Value("${app.file.s3.final-prefix:${APP_FILE_S3_FINAL_PREFIX:final}}") String finalPrefix,
		@Value("${app.file.presign-ttl-minutes:${APP_FILE_PRESIGN_TTL_MINUTES:15}}") long presignTtlMinutes,
		@Value("${app.file.max-size-bytes:${APP_FILE_MAX_SIZE_BYTES:10485760}}") long maxSizeBytes,
		@Value("${app.file.rendition.display-max-px:${APP_FILE_DISPLAY_MAX_PX:1280}}") int displayMaxPx,
		@Value("${app.file.rendition.thumb-max-px:${APP_FILE_THUMB_MAX_PX:320}}") int thumbMaxPx,
		@Value("${app.file.rendition.webp-quality:${APP_FILE_WEBP_QUALITY:80}}") int webpQuality
	) {
		return new FileProperties(
			tmpPrefix,
			finalPrefix,
			Duration.ofMinutes(presignTtlMinutes),
			maxSizeBytes,
			displayMaxPx,
			thumbMaxPx,
			webpQuality
		);
	}

	@Bean
	S3Client s3Client(
		@Value("${aws.region:${AWS_REGION:ap-northeast-2}}") String region,
		@Value("${aws.s3.api-call-timeout-seconds:${AWS_S3_API_CALL_TIMEOUT_SECONDS:10}}") long apiCallTimeoutSeconds,
		@Value("${aws.s3.api-call-attempt-timeout-seconds:${AWS_S3_API_CALL_ATTEMPT_TIMEOUT_SECONDS:3}}") long apiCallAttemptTimeoutSeconds
	) {
		return S3Client.builder()
			.region(Region.of(region))
			.overrideConfiguration(s3ClientOverrideConfiguration(apiCallTimeoutSeconds, apiCallAttemptTimeoutSeconds))
			.build();
	}

	static ClientOverrideConfiguration s3ClientOverrideConfiguration(
		long apiCallTimeoutSeconds,
		long apiCallAttemptTimeoutSeconds
	) {
		return ClientOverrideConfiguration.builder()
			.apiCallTimeout(Duration.ofSeconds(apiCallTimeoutSeconds))
			.apiCallAttemptTimeout(Duration.ofSeconds(apiCallAttemptTimeoutSeconds))
			.build();
	}

	@Bean
	S3Presigner s3Presigner(@Value("${aws.region:${AWS_REGION:ap-northeast-2}}") String region) {
		return S3Presigner.builder()
			.region(Region.of(region))
			.build();
	}

	@Bean
	FileStorage fileStorage(
		S3Client s3Client,
		S3Presigner s3Presigner,
		@Value("${aws.s3.bucket:${AWS_S3_BUCKET:}}") String bucket
	) {
		return new S3FileStorage(s3Client, s3Presigner, bucket);
	}
}
