package shinhan.fibri.ieum;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import shinhan.fibri.ieum.main.auth.session.CsrfDoubleSubmitFilter;
import shinhan.fibri.ieum.main.auth.session.JwtAuthenticationFilter;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MainApplicationTests {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "app_main_context");
	}

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private FilterChainProxy springSecurityFilterChain;

	@Autowired
	private MockMvc mockMvc;

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
	void securityFiltersAreNotRegisteredAsServletFilters() {
		List<? extends FilterRegistrationBean<?>> registrations = applicationContext
			.getBeansOfType(FilterRegistrationBean.class)
			.values()
			.stream()
			.map(registration -> (FilterRegistrationBean<?>) registration)
			.toList();

		assertThat(registrations)
			.anySatisfy(registration -> {
				assertThat(registration.getFilter()).isInstanceOf(JwtAuthenticationFilter.class);
				assertThat(registration.isEnabled()).isFalse();
			})
			.anySatisfy(registration -> {
				assertThat(registration.getFilter()).isInstanceOf(CsrfDoubleSubmitFilter.class);
				assertThat(registration.isEnabled()).isFalse();
			});
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

	@Test
	void corsAllowsNextDevServerWithCredentials() throws Exception {
		mockMvc.perform(options("/api/v1/auth/login")
				.header(HttpHeaders.ORIGIN, "http://localhost:3000")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-csrf-token"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
			.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
	}

	private Path applicationPropertiesPath() {
		Path fromRoot = Path.of("app-main/src/main/resources/application.properties");
		if (Files.exists(fromRoot)) {
			return fromRoot;
		}
		return Path.of("src/main/resources/application.properties");
	}

}
