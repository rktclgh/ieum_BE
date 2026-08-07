package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

class RedisRuntimePropertiesTest {

	@Test
	void mapsAuthenticatedRedisPasswordFromRuntimeEnvironment() throws IOException {
		StandardEnvironment environment = applicationEnvironment(Map.of("REDIS_PASSWORD", "on-prem-secret"));

		assertThat(environment.getProperty("spring.data.redis.password")).isEqualTo("on-prem-secret");
	}

	@Test
	void mapsRedisLogicalDatabaseFromRuntimeEnvironment() throws IOException {
		StandardEnvironment environment = applicationEnvironment(Map.of("REDIS_DATABASE", "1"));

		assertThat(environment.getProperty("spring.data.redis.database")).isEqualTo("1");
	}

	@Test
	void keepsLocalRedisUnauthenticatedWhenNoPasswordIsConfigured() throws IOException {
		StandardEnvironment environment = applicationEnvironment(Map.of());

		assertThat(environment.getProperty("spring.data.redis.password")).isEmpty();
	}

	private StandardEnvironment applicationEnvironment(Map<String, Object> runtimeEnvironment) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("runtimeEnvironment", runtimeEnvironment));
		environment.getPropertySources().addLast(new PropertiesPropertySource("applicationProperties", applicationProperties()));
		return environment;
	}

	private Properties applicationProperties() throws IOException {
		Properties properties = new Properties();
		try (var input = Files.newInputStream(applicationPropertiesPath())) {
			properties.load(input);
		}
		return properties;
	}

	private Path applicationPropertiesPath() {
		Path fromRoot = Path.of("app-main/src/main/resources/application.properties");
		if (Files.exists(fromRoot)) {
			return fromRoot;
		}
		return Path.of("src/main/resources/application.properties");
	}
}
