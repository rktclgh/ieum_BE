package shinhan.fibri.ieum.main.file.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.file.dto.FileCompleteResponse;
import shinhan.fibri.ieum.main.file.dto.FilePresignRequest;
import shinhan.fibri.ieum.main.file.dto.FilePresignResponse;
import shinhan.fibri.ieum.main.file.dto.FileStreamResponse;
import shinhan.fibri.ieum.main.file.exception.FileNotFoundException;
import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;
import shinhan.fibri.ieum.main.file.service.FileService;

@WebMvcTest(FileController.class)
@AutoConfigureMockMvc(addFilters = false)
class FileControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FileService fileService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(fileService);
	}

	@Test
	void presignReturnsFileIdAndUploadUrl() throws Exception {
		UUID fileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		when(fileService.createPresign(any(AuthenticatedUser.class), any(FilePresignRequest.class)))
			.thenReturn(new FilePresignResponse(fileId, URI.create("https://storage.example/upload")));

		mockMvc.perform(post("/api/v1/files/presign")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "purpose": "meeting",
					  "contentType": "image/jpeg",
					  "sizeBytes": 1024
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fileId", is("11111111-1111-1111-1111-111111111111")))
			.andExpect(jsonPath("$.uploadUrl", is("https://storage.example/upload")));

		verify(fileService).createPresign(any(AuthenticatedUser.class), any(FilePresignRequest.class));
	}

	@Test
	void completeReturnsFileId() throws Exception {
		UUID fileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		when(fileService.complete(any(AuthenticatedUser.class), eq(fileId))).thenReturn(new FileCompleteResponse(fileId));

		mockMvc.perform(post("/api/v1/files/{fileId}/complete", fileId).with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.fileId", is("22222222-2222-2222-2222-222222222222")));
	}

	@Test
	void streamFileReturnsImageBytesAndHardeningHeaders() throws Exception {
		UUID fileId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		when(fileService.stream(any(AuthenticatedUser.class), eq(fileId), eq("thumb")))
			.thenReturn(new FileStreamResponse("image/webp", 3L, () -> new ByteArrayInputStream(new byte[] {1, 2, 3})));

		var result = mockMvc.perform(get("/api/v1/files/{fileId}", fileId)
				.param("v", "thumb")
				.with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/webp"))
			.andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 3L))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(request().asyncStarted())
			.andReturn();

		mockMvc.perform(asyncDispatch(result))
			.andExpect(status().isOk())
			.andExpect(content().bytes(new byte[] {1, 2, 3}));
	}

	@Test
	void presignInvalidRequestReturnsBadRequest() throws Exception {
		when(fileService.createPresign(any(AuthenticatedUser.class), any(FilePresignRequest.class)))
			.thenThrow(new InvalidFileRequestException("Only jpeg and png images are supported"));

		mockMvc.perform(post("/api/v1/files/presign")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "purpose": "meeting",
					  "contentType": "image/gif",
					  "sizeBytes": 1024
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_FILE_REQUEST")))
			.andExpect(jsonPath("$.message", is("Only jpeg and png images are supported")));
	}

	@Test
	void streamInvalidVariantReturnsBadRequest() throws Exception {
		UUID fileId = UUID.fromString("44444444-4444-4444-4444-444444444444");
		when(fileService.stream(any(AuthenticatedUser.class), eq(fileId), eq("bad")))
			.thenThrow(new InvalidFileRequestException("Invalid file variant"));

		mockMvc.perform(get("/api/v1/files/{fileId}", fileId)
				.param("v", "bad")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_FILE_REQUEST")))
			.andExpect(jsonPath("$.message", is("Invalid file variant")));
	}

	@Test
	void streamMissingFileReturnsNotFound() throws Exception {
		UUID fileId = UUID.fromString("55555555-5555-5555-5555-555555555555");
		when(fileService.stream(any(AuthenticatedUser.class), eq(fileId), eq("thumb")))
			.thenThrow(new FileNotFoundException());

		mockMvc.perform(get("/api/v1/files/{fileId}", fileId)
				.param("v", "thumb")
				.with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("FILE_NOT_FOUND")))
			.andExpect(jsonPath("$.message", is("File not found")));
	}

	private static RequestPostProcessor authenticated() {
		return request -> {
			AuthenticatedUser principal = new AuthenticatedUser(
				42L,
				"user@example.com",
				UserRole.user,
				UserStatus.active
			);
			SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken(principal, null));
			return request;
		};
	}

	@TestConfiguration
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

		@Bean
		WebMvcConfigurer authenticationPrincipalArgumentResolverConfigurer() {
			return new WebMvcConfigurer() {
				@Override
				public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) {
					resolvers.add(new AuthenticationPrincipalArgumentResolver());
				}
			};
		}
	}
}
