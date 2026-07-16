package shinhan.fibri.ieum.main.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class JpaRequestLifecycleConfigurationTest {

	@Test
	void disablesOpenEntityManagerInViewForLongLivedAsyncRequests() throws IOException {
		Properties properties = new Properties();
		try (var inputStream = new ClassPathResource("application.properties").getInputStream()) {
			properties.load(inputStream);
		}

		assertThat(properties.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
	}
}
