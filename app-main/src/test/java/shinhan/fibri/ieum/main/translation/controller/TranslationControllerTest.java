package shinhan.fibri.ieum.main.translation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.translation.dto.TranslationResponse;
import shinhan.fibri.ieum.main.translation.service.TargetLanguage;
import shinhan.fibri.ieum.main.translation.service.TranslationNotAvailableException;
import shinhan.fibri.ieum.main.translation.service.TranslationProviderUnavailableException;
import shinhan.fibri.ieum.main.translation.service.TranslationRateLimitedException;
import shinhan.fibri.ieum.main.translation.service.TranslationService;

@WebMvcTest(TranslationController.class)
@AutoConfigureMockMvc(addFilters = false)
class TranslationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TranslationService translationService;

	@BeforeEach
	void resetMocks() {
		reset(translationService);
	}

	@Test
	void genericEndpointReturnsOnlyTranslatedText() throws Exception {
		when(translationService.translate(any(), eq("hello"), eq(TargetLanguage.KO)))
			.thenReturn(new TranslationResponse("안녕"));

		mockMvc.perform(post("/api/v1/translate")
				.with(principal(principal()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"hello\",\"targetLang\":\"ko\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.translatedText", is("안녕")))
			.andExpect(jsonPath("$.sourceLang").doesNotExist())
			.andExpect(jsonPath("$.targetLang").doesNotExist());

		verify(translationService).translate(any(), eq("hello"), eq(TargetLanguage.KO));
	}

	@Test
	void genericEndpointRejectsAnonymousAccessBeforeCallingService() throws Exception {
		mockMvc.perform(post("/api/v1/translate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"hello\",\"targetLang\":\"ko\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")));

		verifyNoInteractions(translationService);
	}

	@Test
	void blankTextUsesValidationErrorShape() throws Exception {
		mockMvc.perform(post("/api/v1/translate")
				.with(principal(principal()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"   \",\"targetLang\":\"ko\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("text")));
	}

	@Test
	void tooLongTextUsesValidationErrorShape() throws Exception {
		String longText = "가".repeat(5_001);

		mockMvc.perform(post("/api/v1/translate")
				.with(principal(principal()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"" + longText + "\",\"targetLang\":\"ko\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("text")));
	}

	@Test
	void invalidTargetLangUsesValidationErrorShape() throws Exception {
		mockMvc.perform(post("/api/v1/translate")
				.with(principal(principal()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"hello\",\"targetLang\":\"fr\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("targetLang")));
	}

	@Test
	void nullTargetLangUsesValidationErrorShape() throws Exception {
		mockMvc.perform(post("/api/v1/translate")
				.with(principal(principal()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"hello\",\"targetLang\":null}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("targetLang")));
	}

	@Test
	void malformedBodyUsesValidationErrorShape() throws Exception {
		mockMvc.perform(post("/api/v1/translate")
				.with(principal(principal()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("body")));
	}

	@Test
	void blankContentMapsToNotAvailable() throws Exception {
		when(translationService.translate(any(), eq("hello"), eq(TargetLanguage.KO)))
			.thenThrow(new TranslationNotAvailableException());

		mockMvc.perform(post("/api/v1/translate")
				.with(principal(principal()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"hello\",\"targetLang\":\"ko\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("TRANSLATION_NOT_AVAILABLE")));
	}

	@Test
	void rateLimitMapsTo429() throws Exception {
		when(translationService.translate(any(), eq("hello"), eq(TargetLanguage.KO)))
			.thenThrow(new TranslationRateLimitedException());

		mockMvc.perform(post("/api/v1/translate")
				.with(principal(principal()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"hello\",\"targetLang\":\"ko\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code", is("TRANSLATION_RATE_LIMITED")));
	}

	@Test
	void providerUnavailableMapsTo503() throws Exception {
		when(translationService.translate(any(), eq("hello"), eq(TargetLanguage.KO)))
			.thenThrow(new TranslationProviderUnavailableException());

		mockMvc.perform(post("/api/v1/translate")
				.with(principal(principal()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"hello\",\"targetLang\":\"ko\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code", is("TRANSLATION_UNAVAILABLE")));
	}

	private static AuthenticatedUser principal() {
		return new AuthenticatedUser(42L, "me@example.com", UserRole.user, UserStatus.active);
	}

	private static RequestPostProcessor principal(AuthenticatedUser principal) {
		return request -> {
			request.setAttribute("testPrincipal", principal);
			return request;
		};
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		TranslationService translationService() {
			return mock(TranslationService.class);
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
				public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
					resolvers.add(new HandlerMethodArgumentResolver() {
						@Override
						public boolean supportsParameter(MethodParameter parameter) {
							return parameter.hasParameterAnnotation(
								org.springframework.security.core.annotation.AuthenticationPrincipal.class
							);
						}

						@Override
						public Object resolveArgument(
							MethodParameter parameter,
							ModelAndViewContainer mavContainer,
							NativeWebRequest webRequest,
							WebDataBinderFactory binderFactory
						) {
							return webRequest.getAttribute("testPrincipal", RequestAttributes.SCOPE_REQUEST);
						}
					});
				}
			};
		}
	}
}
