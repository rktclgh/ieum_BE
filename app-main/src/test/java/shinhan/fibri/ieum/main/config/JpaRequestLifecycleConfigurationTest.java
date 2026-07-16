package shinhan.fibri.ieum.main.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class JpaRequestLifecycleConfigurationTest {

	@Test
	void disablesOpenEntityManagerInViewForLongLivedAsyncRequests() throws IOException {
		Properties properties = new Properties();
		try (var inputStream = Files.newInputStream(productionPropertiesPath())) {
			properties.load(inputStream);
		}

		assertThat(properties.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
	}

	private Path productionPropertiesPath() {
		Path moduleRelativePath = Path.of("src", "main", "resources", "application.properties");
		if (Files.isRegularFile(moduleRelativePath)) {
			return moduleRelativePath;
		}
		return Path.of("app-main", "src", "main", "resources", "application.properties");
	}
}
