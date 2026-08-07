package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

class FileConfigTest {

	@Test
	void s3ClientOverrideConfigurationUsesConfiguredTimeouts() {
		var configuration = FileConfig.s3ClientOverrideConfiguration(10L, 3L);

		assertThat(configuration.apiCallTimeout()).contains(Duration.ofSeconds(10L));
		assertThat(configuration.apiCallAttemptTimeout()).contains(Duration.ofSeconds(3L));
	}

	@Test
	void s3PresignerUsesPublicMinioEndpointWithPathStyleAndBrowserCompatibleUrl() {
		var endpoints = FileConfig.s3Endpoints(
			"http://minio:9000",
			"https://files.rktclgh.site",
			true
		);
		var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access-key", "test-secret-key"));

		try (var presigner = FileConfig.s3Presigner("ap-northeast-2", endpoints, credentials)) {
			var presignedRequest = presigner.presignGetObject(
				GetObjectPresignRequest.builder()
					.signatureDuration(Duration.ofMinutes(5))
					.getObjectRequest(GetObjectRequest.builder().bucket("ieum-files").key("tmp/report.png").build())
					.build()
			);

			assertThat(endpoints.apiEndpoint()).isEqualTo(URI.create("http://minio:9000"));
			assertThat(presignedRequest.url().toString())
				.startsWith("https://files.rktclgh.site/ieum-files/tmp/report.png?");
			assertThat(presignedRequest.isBrowserExecutable()).isTrue();
		}
	}

	@Test
	void s3ClientUsesInternalMinioApiEndpoint() {
		var endpoints = FileConfig.s3Endpoints(
			"http://minio:9000",
			"https://files.rktclgh.site",
			true
		);

		try (var s3Client = new FileConfig().s3Client("us-east-1", "ap-northeast-2", 10L, 3L, endpoints)) {
			assertThat(s3Client.serviceClientConfiguration().endpointOverride())
				.contains(URI.create("http://minio:9000"));
			assertThat(s3Client.serviceClientConfiguration().region().id()).isEqualTo("us-east-1");
		}
	}

	@Test
	void s3EndpointsKeepNativeAwsBehaviorWhenNoOverrideIsConfigured() {
		var endpoints = FileConfig.s3Endpoints("", "", false);

		assertThat(endpoints.apiEndpoint()).isNull();
		assertThat(endpoints.presignEndpoint()).isNull();
		assertThat(endpoints.pathStyleAccessEnabled()).isFalse();
	}

	@Test
	void s3EndpointsRejectUnsafePresignEndpoint() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> FileConfig.s3Endpoints("http://minio:9000", "https://user:secret@files.example.com", true))
			.withMessage("AWS_S3_PRESIGN_ENDPOINT must be an absolute HTTP(S) URL without credentials, query, or fragment");
	}

	@Test
	void s3EndpointsRequirePublicPresignEndpointForCustomApiEndpoint() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> FileConfig.s3Endpoints("http://minio:9000", "", true))
			.withMessage("AWS_S3_PRESIGN_ENDPOINT is required when AWS_S3_ENDPOINT is configured");
	}

	@Test
	void s3EndpointsRejectHttpPresignEndpoint() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> FileConfig.s3Endpoints("http://minio:9000", "http://files.example.com", true))
			.withMessage("AWS_S3_PRESIGN_ENDPOINT must use HTTPS");
	}

	@Test
	void s3RegionFallsBackWhenS3SpecificRegionIsBlank() {
		assertThat(FileConfig.s3Region("", "ap-northeast-2")).isEqualTo("ap-northeast-2");
	}

	@Test
	void customS3EndpointRequiresExplicitSigningRegion() {
		var endpoints = FileConfig.s3Endpoints(
			"http://minio:9000",
			"https://files.rktclgh.site",
			true
		);

		assertThatIllegalArgumentException()
			.isThrownBy(() -> new FileConfig().s3Client("", "ap-northeast-2", 10L, 3L, endpoints))
			.withMessage("AWS_S3_REGION is required when AWS_S3_ENDPOINT is configured");
	}

	@Test
	void springWiresSeparateMinioApiAndPresignEndpoints() {
		try (var context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("minio", Map.of(
				"aws.region", "ap-northeast-2",
				"aws.s3.region", "us-east-1",
				"aws.s3.bucket", "ieum-files",
				"aws.s3.endpoint", "http://minio:9000",
				"aws.s3.presign-endpoint", "https://files.rktclgh.site",
				"aws.s3.path-style-access-enabled", "true"
			)));
			context.register(FileConfig.class);
			context.refresh();

			assertThat(context.getBean(S3Client.class).serviceClientConfiguration().endpointOverride())
				.contains(URI.create("http://minio:9000"));
			assertThat(context.getBean(S3Client.class).serviceClientConfiguration().region().id())
				.isEqualTo("us-east-1");
			assertThat(context.getBean(FileConfig.S3Endpoints.class).presignEndpoint())
				.isEqualTo(URI.create("https://files.rktclgh.site"));
		}
	}
}
