package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.model.bedrock.autoconfigure.BedrockAwsConnectionProperties;
import org.springframework.ai.model.bedrock.converse.autoconfigure.BedrockConverseProxyChatAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

class BedrockNoRetryClientConfigurationTest {

	private final ApplicationContextRunner featureOffRunner = new ApplicationContextRunner()
		.withUserConfiguration(BedrockNoRetryClientConfiguration.class);

	private final ApplicationContextRunner springAiRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(BedrockConverseProxyChatAutoConfiguration.class))
		.withUserConfiguration(
			BedrockNoRetryClientConfiguration.class,
			ToolCallingTestConfiguration.class
		)
		.withPropertyValues(
			"spring.ai.model.chat=bedrock-converse",
			"spring.ai.bedrock.aws.region=ap-southeast-2",
			"spring.ai.bedrock.aws.access-key=test-access-key",
			"spring.ai.bedrock.aws.secret-key=test-secret-key",
			"spring.ai.bedrock.aws.timeout=30s",
			"spring.ai.bedrock.aws.connection-timeout=5s",
			"spring.ai.bedrock.aws.async-read-timeout=30s",
			"spring.ai.bedrock.aws.connection-acquisition-timeout=30s",
			"spring.ai.bedrock.aws.socket-timeout=90s",
			"spring.ai.bedrock.converse.chat.options.model=amazon.nova-micro-v1:0"
		);

	@Test
	void bothFeaturesOffCreateNoApplicationOwnedBedrockClients() {
		featureOffRunner
			.withPropertyValues(
				"app.ai.features.question-answer-enabled=false",
				"app.ai.features.report-review-enabled=false"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(BedrockAwsConnectionProperties.class);
				assertThat(context).doesNotHaveBean(BedrockRuntimeClient.class);
				assertThat(context).doesNotHaveBean(BedrockRuntimeAsyncClient.class);
			});
	}

	@Test
	void questionFeatureCreatesSingleAttemptClientsThatSpringAiConsumes() {
		springAiRunner
			.withPropertyValues(
				"app.ai.features.question-answer-enabled=true",
				"app.ai.features.report-review-enabled=false"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				BedrockRuntimeClient syncClient = context.getBean(BedrockRuntimeClient.class);
				BedrockRuntimeAsyncClient asyncClient = context.getBean(BedrockRuntimeAsyncClient.class);

				assertSingleAttemptSydneyTransport(syncClient);
				assertSingleAttemptSydneyTransport(asyncClient);

				BedrockProxyChatModel chatModel = context.getBean(BedrockProxyChatModel.class);
				assertThat(ReflectionTestUtils.getField(chatModel, "bedrockRuntimeClient"))
					.isSameAs(syncClient);
				assertThat(ReflectionTestUtils.getField(chatModel, "bedrockRuntimeAsyncClient"))
					.isSameAs(asyncClient);
			});
	}

	@Test
	void reportFeatureAloneAlsoUsesTheSingleAttemptClients() {
		springAiRunner
			.withPropertyValues(
				"app.ai.features.question-answer-enabled=false",
				"app.ai.features.report-review-enabled=true"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertSingleAttemptSydneyTransport(context.getBean(BedrockRuntimeClient.class));
				assertSingleAttemptSydneyTransport(context.getBean(BedrockRuntimeAsyncClient.class));
			});
	}

	private void assertSingleAttemptSydneyTransport(BedrockRuntimeClient client) {
		assertThat(client.serviceClientConfiguration().region()).isEqualTo(Region.AP_SOUTHEAST_2);
		assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
			.contains(Duration.ofSeconds(30));
		assertThat(client.serviceClientConfiguration().overrideConfiguration().retryStrategy())
			.map(RetryStrategy::maxAttempts)
			.contains(1);
	}

	private void assertSingleAttemptSydneyTransport(BedrockRuntimeAsyncClient client) {
		assertThat(client.serviceClientConfiguration().region()).isEqualTo(Region.AP_SOUTHEAST_2);
		assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
			.contains(Duration.ofSeconds(30));
		assertThat(client.serviceClientConfiguration().overrideConfiguration().retryStrategy())
			.map(RetryStrategy::maxAttempts)
			.contains(1);
	}

	@Configuration(proxyBeanMethods = false)
	static class ToolCallingTestConfiguration {

		@Bean
		ToolCallingManager toolCallingManager() {
			return ToolCallingManager.builder().build();
		}
	}
}
