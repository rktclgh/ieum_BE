package shinhan.fibri.ieum.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.main.file.service.FileProperties;
import shinhan.fibri.ieum.main.file.storage.FileStorage;
import shinhan.fibri.ieum.main.file.storage.S3FileStorage;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
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
		@Value("${app.file.max-source-pixels:${APP_FILE_MAX_SOURCE_PIXELS:50000000}}") long maxSourcePixels,
		@Value("${app.file.max-source-dimension:${APP_FILE_MAX_SOURCE_DIMENSION:16384}}") int maxSourceDimension,
		@Value("${app.file.rendition.display-max-px:${APP_FILE_DISPLAY_MAX_PX:1280}}") int displayMaxPx,
		@Value("${app.file.rendition.thumb-max-px:${APP_FILE_THUMB_MAX_PX:320}}") int thumbMaxPx,
		@Value("${app.file.rendition.webp-quality:${APP_FILE_WEBP_QUALITY:80}}") int webpQuality
	) {
		return new FileProperties(
			tmpPrefix,
			finalPrefix,
			Duration.ofMinutes(presignTtlMinutes),
			maxSizeBytes,
			maxSourcePixels,
			maxSourceDimension,
			displayMaxPx,
			thumbMaxPx,
			webpQuality
		);
	}

	@Bean
	S3Endpoints s3EndpointConfiguration(
		@Value("${aws.s3.endpoint:${AWS_S3_ENDPOINT:}}") String apiEndpoint,
		@Value("${aws.s3.presign-endpoint:${AWS_S3_PRESIGN_ENDPOINT:}}") String presignEndpoint,
		@Value("${aws.s3.path-style-access-enabled:${AWS_S3_PATH_STYLE_ACCESS_ENABLED:false}}") boolean pathStyleAccessEnabled
	) {
		return s3Endpoints(apiEndpoint, presignEndpoint, pathStyleAccessEnabled);
	}

	@Bean
	S3Client s3Client(
		@Value("${aws.s3.region:${AWS_S3_REGION:}}") String s3SpecificRegion,
		@Value("${aws.region:${AWS_REGION:ap-northeast-2}}") String fallbackRegion,
		@Value("${aws.s3.api-call-timeout-seconds:${AWS_S3_API_CALL_TIMEOUT_SECONDS:10}}") long apiCallTimeoutSeconds,
		@Value("${aws.s3.api-call-attempt-timeout-seconds:${AWS_S3_API_CALL_ATTEMPT_TIMEOUT_SECONDS:3}}") long apiCallAttemptTimeoutSeconds,
		S3Endpoints endpoints
	) {
		var region = s3Region(s3SpecificRegion, fallbackRegion, endpoints);
		var builder = S3Client.builder()
			.region(Region.of(region))
			.overrideConfiguration(s3ClientOverrideConfiguration(apiCallTimeoutSeconds, apiCallAttemptTimeoutSeconds))
			.serviceConfiguration(
				S3Configuration.builder()
					.pathStyleAccessEnabled(endpoints.pathStyleAccessEnabled())
					.build()
			);
		if (endpoints.apiEndpoint() != null) {
			builder.endpointOverride(endpoints.apiEndpoint());
		}
		return builder.build();
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
	S3Presigner s3Presigner(
		@Value("${aws.s3.region:${AWS_S3_REGION:}}") String s3SpecificRegion,
		@Value("${aws.region:${AWS_REGION:ap-northeast-2}}") String fallbackRegion,
		S3Endpoints endpoints
	) {
		return s3Presigner(
			s3Region(s3SpecificRegion, fallbackRegion, endpoints),
			endpoints,
			DefaultCredentialsProvider.builder().build()
		);
	}

	static S3Presigner s3Presigner(
		String region,
		S3Endpoints endpoints,
		AwsCredentialsProvider credentialsProvider
	) {
		var builder = S3Presigner.builder()
			.region(Region.of(region))
			.credentialsProvider(credentialsProvider)
			.serviceConfiguration(
				S3Configuration.builder()
					.pathStyleAccessEnabled(endpoints.pathStyleAccessEnabled())
					.build()
			);
		if (endpoints.presignEndpoint() != null) {
			builder.endpointOverride(endpoints.presignEndpoint());
		}
		return builder.build();
	}

	static S3Endpoints s3Endpoints(String apiEndpoint, String presignEndpoint, boolean pathStyleAccessEnabled) {
		var parsedApiEndpoint = parseEndpoint(apiEndpoint, "AWS_S3_ENDPOINT");
		var parsedPresignEndpoint = parseEndpoint(presignEndpoint, "AWS_S3_PRESIGN_ENDPOINT");
		if (parsedApiEndpoint != null && parsedPresignEndpoint == null) {
			throw new IllegalArgumentException("AWS_S3_PRESIGN_ENDPOINT is required when AWS_S3_ENDPOINT is configured");
		}
		if (parsedPresignEndpoint != null && !"https".equalsIgnoreCase(parsedPresignEndpoint.getScheme())) {
			throw new IllegalArgumentException("AWS_S3_PRESIGN_ENDPOINT must use HTTPS");
		}
		return new S3Endpoints(
			parsedApiEndpoint,
			parsedPresignEndpoint,
			pathStyleAccessEnabled
		);
	}

	static String s3Region(String s3SpecificRegion, String fallbackRegion) {
		if (s3SpecificRegion != null && !s3SpecificRegion.isBlank()) {
			return s3SpecificRegion.trim();
		}
		if (fallbackRegion == null || fallbackRegion.isBlank()) {
			throw new IllegalArgumentException("S3 region is required");
		}
		return fallbackRegion.trim();
	}

	static String s3Region(String s3SpecificRegion, String fallbackRegion, S3Endpoints endpoints) {
		if (endpoints.apiEndpoint() != null && (s3SpecificRegion == null || s3SpecificRegion.isBlank())) {
			throw new IllegalArgumentException("AWS_S3_REGION is required when AWS_S3_ENDPOINT is configured");
		}
		return s3Region(s3SpecificRegion, fallbackRegion);
	}

	private static URI parseEndpoint(String value, String propertyName) {
		if (value == null || value.isBlank()) {
			return null;
		}

		URI endpoint;
		try {
			endpoint = URI.create(value.trim());
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(propertyName + " must be a valid HTTP(S) URL", exception);
		}

		if (
			!endpoint.isAbsolute()
				|| endpoint.getHost() == null
				|| !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
				|| endpoint.getUserInfo() != null
				|| endpoint.getQuery() != null
				|| endpoint.getFragment() != null
		) {
			throw new IllegalArgumentException(propertyName + " must be an absolute HTTP(S) URL without credentials, query, or fragment");
		}

		return endpoint;
	}

	record S3Endpoints(URI apiEndpoint, URI presignEndpoint, boolean pathStyleAccessEnabled) {}

	@Bean
	FileStorage fileStorage(
		S3Client s3Client,
		S3Presigner s3Presigner,
		@Value("${aws.s3.bucket:${AWS_S3_BUCKET:}}") String bucket
	) {
		return new S3FileStorage(s3Client, s3Presigner, bucket);
	}
}
