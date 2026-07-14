package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WebGroundingConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(
			WebGroundingConfiguration.class,
			DisabledWebGroundingConfiguration.class
		)
		.withBean(ObjectMapper.class, ObjectMapper::new);

	@Test
	void answerOffCreatesNoWebGroundingBeansEvenWhenWebFlagIsOn() {
		contextRunner
			.withPropertyValues(
				"app.ai.features.question-answer-enabled=false",
				"app.ai.features.web-grounding-enabled=true"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(WebGroundingProperties.class);
				assertThat(context).doesNotHaveBean(Client.class);
				assertThat(context).doesNotHaveBean(WebGroundingGateway.class);
			});
	}

	@Test
	void missingWebFlagCreatesExactlyOneDisabledGatewayWithoutASecret() {
		assertDisabledGateway(contextRunner.withPropertyValues(
			"app.ai.features.question-answer-enabled=true"
		));
	}

	@Test
	void falseWebFlagCreatesExactlyOneDisabledGatewayWithoutASecret() {
		assertDisabledGateway(contextRunner.withPropertyValues(
			"app.ai.features.question-answer-enabled=true",
			"app.ai.features.web-grounding-enabled=false"
		));
	}

	@Test
	void enabledWebGroundingCreatesExactlyOneEnabledGatewayAndDedicatedClient() {
		contextRunner
			.withPropertyValues(validProperties())
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(WebGroundingProperties.class);
				assertThat(context).hasSingleBean(WebGroundingGateway.class);
				assertThat(context).hasSingleBean(GeminiSearchWebGroundingGateway.class);
				assertThat(context).doesNotHaveBean(DisabledWebGroundingGateway.class);
				assertThat(context).hasBean("webGroundingGeminiClient");
				assertThat(context).hasSingleBean(Client.class);
				assertThat(context.getBean(WebGroundingGateway.class).enabled()).isTrue();
			});
	}

	@Test
	void enabledTransportUsesFortyFiveSecondsAndDisablesSdkRetries() {
		WebGroundingProperties properties = properties(Duration.ofSeconds(45));

		HttpOptions options = WebGroundingConfiguration.geminiHttpOptions(properties);

		assertThat(options.timeout()).contains(45_000);
		assertThat(options.retryOptions()).isPresent();
		assertThat(options.retryOptions().orElseThrow().attempts()).contains(1);
		assertThat(options.retryOptions().orElseThrow().httpStatusCodes()).contains(List.of());
	}

	@Test
	void enabledWebGroundingFailsFastForBlankKeyOrWrongTimeout() {
		contextRunner
			.withPropertyValues(validProperties())
			.withPropertyValues(
				"app.ai.question-answer.web-grounding.gemini-api-key="
			)
			.run(context -> assertThat(context).hasFailed());

		contextRunner
			.withPropertyValues(validProperties())
			.withPropertyValues(
				"app.ai.question-answer.web-grounding.model-timeout=44s"
			)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void featureMatrixNeverCreatesMoreThanOneGateway() {
		assertGatewayCount(contextRunner.withPropertyValues(
			"app.ai.features.question-answer-enabled=false",
			"app.ai.features.web-grounding-enabled=true"
		), 0);
		assertGatewayCount(contextRunner.withPropertyValues(
			"app.ai.features.question-answer-enabled=true"
		), 1);
		assertGatewayCount(contextRunner.withPropertyValues(
			"app.ai.features.question-answer-enabled=true",
			"app.ai.features.web-grounding-enabled=false"
		), 1);
		assertGatewayCount(contextRunner.withPropertyValues(validProperties()), 1);
	}

	private void assertDisabledGateway(ApplicationContextRunner runner) {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(WebGroundingProperties.class);
			assertThat(context).doesNotHaveBean(Client.class);
			assertThat(context).hasSingleBean(WebGroundingGateway.class);
			assertThat(context).hasSingleBean(DisabledWebGroundingGateway.class);
			assertThat(context).doesNotHaveBean(GeminiSearchWebGroundingGateway.class);
			assertThat(context.getBean(WebGroundingGateway.class).enabled()).isFalse();
		});
	}

	private void assertGatewayCount(ApplicationContextRunner runner, int expectedCount) {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeansOfType(WebGroundingGateway.class))
				.hasSize(expectedCount);
		});
	}

	private String[] validProperties() {
		return new String[] {
			"app.ai.features.question-answer-enabled=true",
			"app.ai.features.web-grounding-enabled=true",
			"app.ai.question-answer.web-grounding.model=gemini-3.1-flash-lite",
			"app.ai.question-answer.web-grounding.gemini-api-key=test-only-web-key",
			"app.ai.question-answer.web-grounding.prompt-version=question-web-grounding-v1",
			"app.ai.question-answer.web-grounding.max-tokens=1024",
			"app.ai.question-answer.web-grounding.model-timeout=45s"
		};
	}

	private WebGroundingProperties properties(Duration timeout) {
		return new WebGroundingProperties(
			"gemini-3.1-flash-lite",
			"test-only-web-key",
			"question-web-grounding-v1",
			1024,
			timeout
		);
	}
}
