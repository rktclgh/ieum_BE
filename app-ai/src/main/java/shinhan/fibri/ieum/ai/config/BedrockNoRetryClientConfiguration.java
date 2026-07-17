package shinhan.fibri.ieum.ai.config;

import org.springframework.ai.model.bedrock.autoconfigure.BedrockAwsConnectionProperties;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.providers.AwsRegionProvider;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration(proxyBeanMethods = false)
@Conditional(BedrockNoRetryClientConfiguration.AnyAiBedrockFeatureEnabledCondition.class)
public class BedrockNoRetryClientConfiguration {

	private static final int ASYNC_MAX_CONCURRENCY = 200;

	@Bean(destroyMethod = "close")
	BedrockRuntimeClient bedrockRuntimeClient(
		AwsCredentialsProvider credentialsProvider,
		AwsRegionProvider regionProvider,
		BedrockAwsConnectionProperties properties
	) {
		return BedrockRuntimeClient.builder()
			.credentialsProvider(credentialsProvider)
			.region(regionProvider.getRegion())
			.httpClientBuilder(ApacheHttpClient.builder()
				.connectionAcquisitionTimeout(properties.getConnectionAcquisitionTimeout())
				.connectionTimeout(properties.getConnectionTimeout())
				.socketTimeout(properties.getSocketTimeout()))
			.overrideConfiguration(singleAttemptOverride(properties))
			.build();
	}

	@Bean(destroyMethod = "close")
	BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient(
		AwsCredentialsProvider credentialsProvider,
		AwsRegionProvider regionProvider,
		BedrockAwsConnectionProperties properties
	) {
		return BedrockRuntimeAsyncClient.builder()
			.credentialsProvider(credentialsProvider)
			.region(regionProvider.getRegion())
			.httpClientBuilder(NettyNioAsyncHttpClient.builder()
				.tcpKeepAlive(true)
				.readTimeout(properties.getAsyncReadTimeout())
				.connectionTimeout(properties.getConnectionTimeout())
				.connectionAcquisitionTimeout(properties.getConnectionAcquisitionTimeout())
				.maxConcurrency(ASYNC_MAX_CONCURRENCY))
			.overrideConfiguration(singleAttemptOverride(properties))
			.build();
	}

	private ClientOverrideConfiguration singleAttemptOverride(
		BedrockAwsConnectionProperties properties
	) {
		return ClientOverrideConfiguration.builder()
			.apiCallTimeout(properties.getTimeout())
			.retryStrategy(StandardRetryStrategy.builder()
				.maxAttempts(1)
				.build())
			.build();
	}

	static final class AnyAiBedrockFeatureEnabledCondition extends AnyNestedCondition {

		AnyAiBedrockFeatureEnabledCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnProperty(
			prefix = "app.ai.features",
			name = "question-answer-enabled",
			havingValue = "true"
		)
		static final class QuestionAnswerEnabled {
		}

		@ConditionalOnProperty(
			prefix = "app.ai.features",
			name = "report-review-enabled",
			havingValue = "true"
		)
		static final class ReportReviewEnabled {
		}

		@ConditionalOnProperty(
			prefix = "app.ai.features",
			name = "knowledge-relation-extraction-enabled",
			havingValue = "true"
		)
		static final class KnowledgeRelationExtractionEnabled {
		}
	}
}
