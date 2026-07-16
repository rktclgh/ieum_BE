package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbeddingProperties;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProperties;
import shinhan.fibri.ieum.ai.question.webgrounding.WebGroundingProperties;

class GeminiApiKeyEnvironmentContractTest {

	@Test
	void bindsOneCommonKeyToEveryGeminiClient() throws IOException {
		assertAllGeminiPropertiesUse("APP_AI_GEMINI_API_KEY", "common-key");
	}

	@Test
	void acceptsTheExistingReportKeyAsTheCommonKeyFallback() throws IOException {
		assertAllGeminiPropertiesUse("APP_AI_REPORT_GEMINI_API_KEY", "legacy-report-key");
	}

	private void assertAllGeminiPropertiesUse(String environmentKey, String expectedKey) throws IOException {
		Binder binder = Binder.get(environment(environmentKey, expectedKey));

		assertThat(bind(binder, "app.ai.report", ReportModelProperties.class).geminiApiKey()).isEqualTo(expectedKey);
		assertThat(bind(binder, "app.ai.question-answer.embedding", QuestionEmbeddingProperties.class).geminiApiKey())
			.isEqualTo(expectedKey);
		assertThat(bind(binder, "app.ai.question-answer.generation", LocalAnswerProperties.class).geminiApiKey())
			.isEqualTo(expectedKey);
		assertThat(bind(binder, "app.ai.question-answer.web-grounding", WebGroundingProperties.class).geminiApiKey())
			.isEqualTo(expectedKey);
	}

	private StandardEnvironment environment(String environmentKey, String value) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(environmentKey, value)));
		environment.getPropertySources().addLast(new PropertiesPropertySource("application", applicationProperties()));
		return environment;
	}

	private <T> T bind(Binder binder, String prefix, Class<T> type) {
		return binder.bind(prefix, Bindable.of(type))
			.orElseThrow(() -> new AssertionError("Missing bound properties for " + prefix));
	}

	private Properties applicationProperties() throws IOException {
		Properties properties = new Properties();
		try (var input = new ClassPathResource("application.properties").getInputStream()) {
			properties.load(input);
		}
		return properties;
	}
}
