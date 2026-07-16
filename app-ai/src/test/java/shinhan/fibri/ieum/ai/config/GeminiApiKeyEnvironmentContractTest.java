package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class GeminiApiKeyEnvironmentContractTest {

	@Test
	void bindsOnlyTheCommonGeminiEnvironmentKey() throws IOException {
		GeminiApiKeyProperties properties = bind(
			Binder.get(environment("APP_AI_GEMINI_API_KEY", "common-key")),
			"app.ai",
			GeminiApiKeyProperties.class
		);

		assertThat(properties.geminiApiKey()).isEqualTo("common-key");
		assertThat(GeminiApiKeyProperties.ENVIRONMENT_VARIABLE).isEqualTo("APP_AI_GEMINI_API_KEY");
	}

	@Test
	void rejectsAFormerPurposeSpecificGeminiEnvironmentKey() throws IOException {
		Binder binder = Binder.get(environment("APP_AI_REPORT_GEMINI_API_KEY", "legacy-report-key"));

		assertThatThrownBy(() -> bind(binder, "app.ai", GeminiApiKeyProperties.class))
			.isInstanceOf(BindException.class)
			.hasRootCauseInstanceOf(IllegalArgumentException.class)
			.hasRootCauseMessage("APP_AI_GEMINI_API_KEY must not be blank");
	}

	private StandardEnvironment environment(String environmentKey, String value) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
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
