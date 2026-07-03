package shinhan.fibri.ieum.main.auth.session;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
class ProtectedEndpointSecurityTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void protectedEndpointReturnsJsonUnauthorizedWhenAccessCookieIsMissing() throws Exception {
		mockMvc.perform(get("/api/v1/protected/ping"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")))
			.andExpect(jsonPath("$.message", is("Authentication is required")));
	}

	@RestController
	static class ProtectedController {

		@GetMapping("/api/v1/protected/ping")
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
