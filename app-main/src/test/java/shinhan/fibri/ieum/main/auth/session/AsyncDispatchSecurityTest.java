package shinhan.fibri.ieum.main.auth.session;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.config.JsonAccessDeniedHandler;
import shinhan.fibri.ieum.config.JsonAuthenticationEntryPoint;
import shinhan.fibri.ieum.config.SecurityConfig;

@WebMvcTest(AsyncDispatchSecurityTest.ProtectedController.class)
@Import({
	SecurityConfig.class,
	JwtAuthenticationFilter.class,
	CsrfDoubleSubmitFilter.class,
	JsonAuthenticationEntryPoint.class,
	JsonAccessDeniedHandler.class
})
@ImportAutoConfiguration({
	SecurityAutoConfiguration.class,
	ServletWebSecurityAutoConfiguration.class,
	SecurityFilterAutoConfiguration.class
})
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:3000")
class AsyncDispatchSecurityTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void normalRequestToProtectedApiStillRequiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/async-security/ping"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")));
	}

	@Test
	void asyncRedispatchOfProtectedApiDoesNotRequireANewAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/async-security/ping")
				.with(request -> {
					request.setDispatcherType(DispatcherType.ASYNC);
					return request;
				}))
			.andExpect(status().isOk())
			.andExpect(content().string("ok"));
	}

	@RestController
	static class ProtectedController {

		@GetMapping("/api/v1/async-security/ping")
		String ping() {
			return "ok";
		}
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}

		@Bean
		ProtectedController protectedController() {
			return new ProtectedController();
		}
	}
}
