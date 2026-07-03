package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@WebMvcTest(GlobalExceptionHandlerTest.FailingController.class)
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void unexpectedExceptionReturnsGenericJsonInternalServerError(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/api/v1/test/fail"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code", is("INTERNAL_SERVER_ERROR")))
			.andExpect(jsonPath("$.message", is("Internal server error")))
			.andExpect(content().string(not(containsString("sensitive failure detail"))));

		assertThat(output).contains("Unhandled exception");
		assertThat(output).contains("sensitive failure detail");
	}

	@Test
	void unsupportedMediaTypeReturnsGenericJsonError() throws Exception {
		mockMvc.perform(post("/api/v1/test/json-only")
				.contentType("text/plain")
				.content("plain text"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.code", is("UNSUPPORTED_MEDIA_TYPE")))
			.andExpect(jsonPath("$.message", is("Unsupported media type")));
	}

	@RestController
	static class FailingController {

		@GetMapping("/api/v1/test/fail")
		String fail() {
			throw new IllegalStateException("sensitive failure detail");
		}

		@PostMapping(value = "/api/v1/test/json-only", consumes = "application/json")
		String jsonOnly() {
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
		FailingController failingController() {
			return new FailingController();
		}
	}
}
