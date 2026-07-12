package shinhan.fibri.ieum;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import shinhan.fibri.ieum.ai.support.AiDatabaseIntegrationTestBase;
import shinhan.fibri.ieum.ai.support.NoNetworkProviderTestConfiguration;

@SpringBootTest
@Import(NoNetworkProviderTestConfiguration.class)
class AiApplicationTests extends AiDatabaseIntegrationTestBase {

	@Test
	void contextLoads() {
	}

	@Test
	void applicationPropertiesImportsModuleEnvFile() throws IOException {
		Properties properties = new Properties();
		try (var input = Files.newInputStream(applicationPropertiesPath())) {
			properties.load(input);
		}

		assertThat(properties.getProperty("spring.config.import"))
			.contains("optional:file:./app-ai/.env[.properties]");
		assertThat(properties)
			.doesNotContainKey("spring.jpa.properties.hibernate.dialect");
	}

	private Path applicationPropertiesPath() {
		Path fromRoot = Path.of("app-ai/src/main/resources/application.properties");
		if (Files.exists(fromRoot)) {
			return fromRoot;
		}
		return Path.of("src/main/resources/application.properties");
	}

}
