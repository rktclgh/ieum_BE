package shinhan.fibri.ieum.main.file.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import shinhan.fibri.ieum.config.JsonAccessDeniedHandler;
import shinhan.fibri.ieum.config.JsonAuthenticationEntryPoint;
import shinhan.fibri.ieum.config.SecurityConfig;
import shinhan.fibri.ieum.main.auth.session.CsrfDoubleSubmitFilter;
import shinhan.fibri.ieum.main.auth.session.JwtAuthenticationFilter;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.file.dto.FileStreamResponse;
import shinhan.fibri.ieum.main.file.service.FileService;

@WebMvcTest(FileController.class)
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
class FileSecurityFilterTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FileService fileService;

	@Test
	void anonymousStreamRequestCanAccessFileEndpoint() throws Exception {
		UUID fileId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		when(fileService.stream(any(), any(UUID.class), any()))
			.thenReturn(new FileStreamResponse("image/webp", 3L, () -> new ByteArrayInputStream(new byte[] {1, 2, 3})));

		var result = mockMvc.perform(get("/api/v1/files/{fileId}", fileId))
			.andExpect(status().isOk())
			.andExpect(request().asyncStarted())
			.andReturn();

		mockMvc.perform(asyncDispatch(result))
			.andExpect(status().isOk());

		verify(fileService).stream(any(), any(UUID.class), any());
	}

	@org.springframework.boot.test.context.TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		FileService fileService() {
			return mock(FileService.class);
		}

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}
	}
}
