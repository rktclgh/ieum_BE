package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@WebMvcTest(GlobalExceptionHandlerTest.FailingController.class)
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void unknownFrontendGetReturnsNextHtmlWithNotFoundStatus() throws Exception {
		mockMvc.perform(get("/unknown/frontend/path"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andExpect(content().string(containsString("NEXT_STATIC_404")));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"/apiary/unknown",
		"/swagger-ui.html.evil",
		"/v3/api-docs.yaml.evil"
	})
	void backendLikePrefixWithoutPathBoundaryStillUsesFrontendHtml404(String path) throws Exception {
		mockMvc.perform(get(path))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andExpect(content().string(containsString("NEXT_STATIC_404")));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"/api/unknown",
		"/ws/unknown",
		"/actuator/unknown",
		"/swagger-ui/unknown",
		"/swagger-ui.html",
		"/v3/api-docs/unknown",
		"/v3/api-docs.yaml"
	})
	void unknownBackendOrOperationsGetKeepsJsonNotFoundContract(String path) throws Exception {
		mockMvc.perform(get(path))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code", is("NOT_FOUND")))
			.andExpect(jsonPath("$.message", is("Resource not found")));
	}

	@Test
	void backendPathBehindContextPathKeepsJsonNotFoundContract() throws Exception {
		mockMvc.perform(get("/ieum/api/unknown").contextPath("/ieum"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code", is("NOT_FOUND")));
	}

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

	@Test
	void disconnectedAsyncRequestDoesNotAttemptToWriteJsonError(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/api/v1/test/disconnected").accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isOk())
			.andExpect(content().string(""));

		assertThat(output).doesNotContain("Unhandled exception");
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

		@GetMapping(value = "/api/v1/test/disconnected", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
		void disconnected() throws AsyncRequestNotUsableException {
			throw new AsyncRequestNotUsableException("Servlet container error notification for disconnected client");
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
