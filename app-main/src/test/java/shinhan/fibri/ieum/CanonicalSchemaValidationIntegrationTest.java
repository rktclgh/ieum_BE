package shinhan.fibri.ieum;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
		assertThat(mainApplicationProperties().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
	}

	private Properties mainApplicationProperties() throws IOException {
		var resources = getClass().getClassLoader().getResources("application.properties");
		while (resources.hasMoreElements()) {
			Properties properties = new Properties();
			try (var input = resources.nextElement().openStream()) {
				properties.load(input);
			}
			if ("ieum-main".equals(properties.getProperty("spring.application.name"))) {
				return properties;
			}
		}
		throw new IllegalStateException("app-main application.properties was not found on the classpath");
	}
}
