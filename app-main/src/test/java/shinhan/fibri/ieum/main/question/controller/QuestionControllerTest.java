package shinhan.fibri.ieum.main.question.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
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
import shinhan.fibri.ieum.main.pin.exception.InvalidPinRequestException;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;
import shinhan.fibri.ieum.main.question.dto.AnswerItem;
import shinhan.fibri.ieum.main.question.dto.AuthorSummary;
import shinhan.fibri.ieum.main.question.dto.CursorPage;
import shinhan.fibri.ieum.main.question.dto.MyQuestionItem;
import shinhan.fibri.ieum.main.question.dto.QuestionCreateRequest;
import shinhan.fibri.ieum.main.question.dto.QuestionDetailResponse;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;
import shinhan.fibri.ieum.main.question.service.QuestionService;

@WebMvcTest(QuestionController.class)
@AutoConfigureMockMvc(addFilters = false)
class QuestionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private QuestionService questionService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(questionService);
	}

	@Test
	void createQuestionReturnsCreatedLocationAndBody() throws Exception {
		when(questionService.create(any(AuthenticatedUser.class), any(QuestionCreateRequest.class)))
			.thenReturn(detailResponse());

		mockMvc.perform(post("/api/v1/questions")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "title",
					  "content": "content",
				  "location": { "lat": 37.4979, "lng": 127.0276, "address": "서울특별시 강남구", "detailAddress": "", "label": "강남역" },
					  "imageFileIds": ["00000000-0000-0000-0000-000000000001"]
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/questions/200"))
			.andExpect(jsonPath("$.questionId", is(200)))
			.andExpect(jsonPath("$.imageUrls[0]", is("/api/v1/files/00000000-0000-0000-0000-000000000001?v=display")));
	}

	@Test
	void getQuestionDetailReturnsBody() throws Exception {
		when(questionService.getDetail(200L)).thenReturn(detailResponse());

		mockMvc.perform(get("/api/v1/questions/200").with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title", is("title")))
			.andExpect(jsonPath("$.author.nickname", is("nickname")))
			.andExpect(jsonPath("$.location.address", is("서울특별시 강남구")));
	}

	@Test
	void listMineReturnsCursorPage() throws Exception {
		when(questionService.listMine(any(AuthenticatedUser.class), eq(null), eq(20)))
			.thenReturn(new CursorPage<>(
				List.of(new MyQuestionItem(
					200L,
					"title",
					false,
					"/api/v1/files/00000000-0000-0000-0000-000000000001?v=thumb",
					2,
					OffsetDateTime.parse("2026-07-08T10:00:00Z")
				)),
				"bmV4dA"
			));

		mockMvc.perform(get("/api/v1/questions/me").with(authenticated()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].thumbnailUrl", is("/api/v1/files/00000000-0000-0000-0000-000000000001?v=thumb")))
			.andExpect(jsonPath("$.nextCursor", is("bmV4dA")));
	}

	@Test
	void updateQuestionIsNotSupported() throws Exception {
		mockMvc.perform(patch("/api/v1/questions/200")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "updated",
					  "imageFileIds": []
					}
					"""))
			.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void deleteQuestionReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/v1/questions/200").with(authenticated()))
			.andExpect(status().isNoContent());

		verify(questionService).delete(any(AuthenticatedUser.class), eq(200L));
	}

	@Test
	void missingQuestionMapsTo404() throws Exception {
		when(questionService.getDetail(999L)).thenThrow(new QuestionNotFoundException());

		mockMvc.perform(get("/api/v1/questions/999").with(authenticated()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("QUESTION_NOT_FOUND")));
	}

	@Test
	void invalidCursorMapsToInvalidCursor() throws Exception {
		when(questionService.listMine(any(AuthenticatedUser.class), eq("bad"), eq(20)))
			.thenThrow(new InvalidPinRequestException("INVALID_CURSOR", "cursor", "Invalid cursor"));

		mockMvc.perform(get("/api/v1/questions/me")
				.param("cursor", "bad")
			.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_CURSOR")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("cursor")));
	}

	@Test
	void invalidPathVariableMapsToValidationFailed() throws Exception {
		mockMvc.perform(get("/api/v1/questions/not-a-number").with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("questionId")));
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

	private QuestionDetailResponse detailResponse() {
		return new QuestionDetailResponse(
			200L,
			"title",
			"content",
			false,
			new AuthorSummary(42L, "nickname", null, "KR"),
			new LocationSnapshot(37.4979, 127.0276, "서울특별시 강남구", "", "강남역"),
			List.of("/api/v1/files/00000000-0000-0000-0000-000000000001?v=display"),
			List.<AnswerItem>of(),
			OffsetDateTime.parse("2026-07-14T00:00:00Z"),
			OffsetDateTime.parse("2026-07-14T00:00:00Z")
		);
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		QuestionService questionService() {
			return mock(QuestionService.class);
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
