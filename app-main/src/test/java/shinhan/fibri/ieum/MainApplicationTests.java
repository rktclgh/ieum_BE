package shinhan.fibri.ieum;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import shinhan.fibri.ieum.main.auth.session.CsrfDoubleSubmitFilter;

@SpringBootTest
class MainApplicationTests {

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Test
	void contextLoads() {
	}

	@Test
	void securityFilterChainIsConfigured() {
		assertThat(applicationContext.getBean(SecurityFilterChain.class)).isNotNull();
	}

	@Test
	void securityFilterChainUsesCustomCsrfFilterOnly() {
		List<Filter> filters = springSecurityFilterChain.getFilters("/api/v1/users/me");

		assertThat(filters).anyMatch(CsrfDoubleSubmitFilter.class::isInstance);
		assertThat(filters).noneMatch(CsrfFilter.class::isInstance);
	}

	@Test
	void applicationPropertiesImportsModuleEnvFile() throws IOException {
		Properties properties = new Properties();
		try (var input = Files.newInputStream(applicationPropertiesPath())) {
			properties.load(input);
		}

		assertThat(properties.getProperty("spring.config.import"))
			.contains("optional:file:./app-main/.env[.properties]");
	}

	private Path applicationPropertiesPath() {
		Path fromRoot = Path.of("app-main/src/main/resources/application.properties");
		if (Files.exists(fromRoot)) {
			return fromRoot;
		}
		return Path.of("src/main/resources/application.properties");
	}

}
