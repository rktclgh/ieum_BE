package shinhan.fibri.ieum.main.answer.controller;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import shinhan.fibri.ieum.main.answer.dto.CreateAnswerRequest;
import shinhan.fibri.ieum.main.answer.dto.CreateAnswerResponse;
import shinhan.fibri.ieum.main.answer.dto.FinalizeAcceptedAnswersRequest;
import shinhan.fibri.ieum.main.answer.dto.FinalizeAcceptedAnswersResponse;
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.answer.exception.AnswerSelectionFinalizedException;
import shinhan.fibri.ieum.main.answer.exception.InvalidAnswerRequestException;
import shinhan.fibri.ieum.main.answer.exception.SelfAcceptanceNotAllowedException;
import shinhan.fibri.ieum.main.answer.service.AnswerService;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;

@WebMvcTest(AnswerController.class)
@AutoConfigureMockMvc(addFilters = false)
class AnswerControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AnswerService answerService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(answerService);
	}

	@Test
	void createAnswerReturnsCreatedLocationAndAnswerId() throws Exception {
		when(answerService.create(any(AuthenticatedUser.class), eq(200L), any(CreateAnswerRequest.class)))
			.thenReturn(new CreateAnswerResponse(300L));

		mockMvc.perform(post("/api/v1/questions/200/answer")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "content": "answer body",
					  "imageFileIds": ["00000000-0000-0000-0000-000000000001"]
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
			.andExpect(jsonPath("$.answerId", is(300)));
	}

	@Test
	void finalizeAcceptedAnswersReturnsCanonicalFinalizedResponse() throws Exception {
		when(answerService.finalizeSelection(
			any(AuthenticatedUser.class),
			eq(5L),
			any(FinalizeAcceptedAnswersRequest.class)
		)).thenReturn(new FinalizeAcceptedAnswersResponse(5L, true, List.of(12L, 15L, 19L)));

		mockMvc.perform(put("/api/v1/questions/5/accepted-answers")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "answerIds": [19, 12, 15]
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.questionId", is(5)))
			.andExpect(jsonPath("$.answerSelectionFinalized", is(true)))
			.andExpect(jsonPath("$.acceptedAnswerIds[0]", is(12)))
			.andExpect(jsonPath("$.acceptedAnswerIds[1]", is(15)))
			.andExpect(jsonPath("$.acceptedAnswerIds[2]", is(19)));

		verify(answerService).finalizeSelection(
			any(AuthenticatedUser.class),
			eq(5L),
			any(FinalizeAcceptedAnswersRequest.class)
		);
	}

	@Test
	void finalizeAcceptedAnswersRejectsEmptyAndNullIds() throws Exception {
		mockMvc.perform(put("/api/v1/questions/5/accepted-answers")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "answerIds": []
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));

		mockMvc.perform(put("/api/v1/questions/5/accepted-answers")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "answerIds": [12, null]
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", startsWith("answerIds")));

		verifyNoInteractions(answerService);
	}

	@Test
	void finalizedConflictMapsToAnswerSelectionFinalized() throws Exception {
		doThrow(new AnswerSelectionFinalizedException())
			.when(answerService).finalizeSelection(
				any(AuthenticatedUser.class),
				eq(5L),
				any(FinalizeAcceptedAnswersRequest.class)
			);

		mockMvc.perform(put("/api/v1/questions/5/accepted-answers")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "answerIds": [12, 15]
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("ANSWER_SELECTION_FINALIZED")));
	}

	@Test
	void createAfterFinalizationMapsToAnswerSelectionFinalized() throws Exception {
		when(answerService.create(any(AuthenticatedUser.class), eq(5L), any(CreateAnswerRequest.class)))
			.thenThrow(new AnswerSelectionFinalizedException());

		mockMvc.perform(post("/api/v1/questions/5/answer")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "content": "late answer"
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("ANSWER_SELECTION_FINALIZED")));
	}

	@Test
	void legacySingleAcceptEndpointIsNotExposed() throws Exception {
		mockMvc.perform(post("/api/v1/answers/300/accept").with(authenticated()))
			.andExpect(status().isNotFound());
	}

	@Test
	void blankContentAndEmptyImagesMapsToValidationFailed() throws Exception {
		when(answerService.create(any(AuthenticatedUser.class), eq(200L), any(CreateAnswerRequest.class)))
			.thenThrow(new InvalidAnswerRequestException("VALIDATION_FAILED", "content", "content or imageFileIds is required"));

		mockMvc.perform(post("/api/v1/questions/200/answer")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "content": " ",
					  "imageFileIds": []
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("content")));
	}

	@Test
	void createAnswerAllowsImagesOnlyWithoutContent() throws Exception {
		when(answerService.create(any(AuthenticatedUser.class), eq(200L), any(CreateAnswerRequest.class)))
			.thenReturn(new CreateAnswerResponse(301L));

		mockMvc.perform(post("/api/v1/questions/200/answer")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "imageFileIds": ["00000000-0000-0000-0000-000000000001"]
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.answerId", is(301)));
	}

	@Test
	void invalidImageMapsToBadRequest() throws Exception {
		when(answerService.create(any(AuthenticatedUser.class), eq(200L), any(CreateAnswerRequest.class)))
			.thenThrow(new InvalidAnswerRequestException("INVALID_IMAGE", "imageFileIds", "Invalid image"));

		mockMvc.perform(post("/api/v1/questions/200/answer")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "content": "answer body",
					  "imageFileIds": ["00000000-0000-0000-0000-000000000001"]
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_IMAGE")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("imageFileIds")));
	}

	@Test
	void missingAnswerMapsTo404() throws Exception {
		when(answerService.create(any(AuthenticatedUser.class), eq(999L), any(CreateAnswerRequest.class)))
			.thenThrow(new QuestionNotFoundException());

		mockMvc.perform(post("/api/v1/questions/999/answer")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "content": "answer body"
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("QUESTION_NOT_FOUND")));

		doThrow(new AnswerNotFoundException())
			.when(answerService).finalizeSelection(
				any(AuthenticatedUser.class),
				eq(998L),
				any(FinalizeAcceptedAnswersRequest.class)
			);

		mockMvc.perform(put("/api/v1/questions/998/accepted-answers")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "answerIds": [300]
					}
					"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("ANSWER_NOT_FOUND")));
	}

	@Test
	void forbiddenAcceptMapsTo403() throws Exception {
		doThrow(new QuestionForbiddenException())
			.when(answerService).finalizeSelection(
				any(AuthenticatedUser.class),
				eq(300L),
				any(FinalizeAcceptedAnswersRequest.class)
			);

		mockMvc.perform(put("/api/v1/questions/300/accepted-answers")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "answerIds": [400]
					}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code", is("FORBIDDEN")));
	}

	@Test
	void selfAcceptanceMapsTo400() throws Exception {
		doThrow(new SelfAcceptanceNotAllowedException())
			.when(answerService).finalizeSelection(
				any(AuthenticatedUser.class),
				eq(300L),
				any(FinalizeAcceptedAnswersRequest.class)
			);

		mockMvc.perform(put("/api/v1/questions/300/accepted-answers")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "answerIds": [400]
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("SELF_ACCEPT_NOT_ALLOWED")));
	}

	@Test
	void invalidPathVariableMapsToValidationFailed() throws Exception {
		mockMvc.perform(put("/api/v1/questions/not-a-number/accepted-answers")
				.with(authenticated())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "answerIds": [400]
					}
					"""))
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

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		AnswerService answerService() {
			return mock(AnswerService.class);
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
