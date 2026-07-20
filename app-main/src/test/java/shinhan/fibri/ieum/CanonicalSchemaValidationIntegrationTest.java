package shinhan.fibri.ieum;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class CanonicalSchemaValidationIntegrationTest {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "canonical_schema_validation");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
	}

	@Test
	void applicationContextValidatesAgainstCanonicalSchema() {
	}

	@Test
	void runtimeConfigurationEnablesSchemaValidation() throws IOException {
		Properties properties = new Properties();
		try (var input = Files.newInputStream(applicationPropertiesPath())) {
			properties.load(input);
		}

		assertThat(properties.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
	}

	private Path applicationPropertiesPath() {
		Path fromRoot = Path.of("app-main/src/main/resources/application.properties");
		if (Files.exists(fromRoot)) {
			return fromRoot;
		}
		return Path.of("src/main/resources/application.properties");
	}
}
