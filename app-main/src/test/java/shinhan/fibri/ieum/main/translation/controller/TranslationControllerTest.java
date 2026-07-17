package shinhan.fibri.ieum.main.translation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
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

	@Test
	void answerEndpointReturnsExactSuccessShape() throws Exception {
		when(translationService.translateAnswer(any(), eq(10L), eq(TargetLanguage.KO)))
			.thenReturn(new TranslationResponse("안녕", "en"));

		mockMvc.perform(post("/api/v1/answers/10/translation")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetLang\":\"ko\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.translatedText", is("안녕")))
			.andExpect(jsonPath("$.sourceLang", is("en")))
			.andExpect(jsonPath("$.targetLang").doesNotExist());
	}

	@Test
	void chatEndpointReturnsExactSuccessShape() throws Exception {
		when(translationService.translateChatMessage(any(), eq(99L), eq(TargetLanguage.EN)))
			.thenReturn(new TranslationResponse("hello", "ja"));

		mockMvc.perform(post("/api/v1/chat/messages/99/translation")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetLang\":\"en\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.translatedText", is("hello")))
			.andExpect(jsonPath("$.sourceLang", is("ja")));
	}

	@Test
	void invalidTargetLangUsesValidationErrorShape() throws Exception {
		mockMvc.perform(post("/api/v1/answers/10/translation")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetLang\":\"fr\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("targetLang")));
	}

	@Test
	void nullTargetLangUsesValidationErrorShape() throws Exception {
		mockMvc.perform(post("/api/v1/answers/10/translation")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetLang\":null}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("targetLang")));
	}

	@Test
	void blankContentMapsToNotAvailable() throws Exception {
		when(translationService.translateAnswer(any(), eq(10L), eq(TargetLanguage.KO)))
			.thenThrow(new TranslationNotAvailableException());

		mockMvc.perform(post("/api/v1/answers/10/translation")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetLang\":\"ko\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("TRANSLATION_NOT_AVAILABLE")));
	}

	@Test
	void rateLimitMapsTo429() throws Exception {
		when(translationService.translateAnswer(any(), eq(10L), eq(TargetLanguage.KO)))
			.thenThrow(new TranslationRateLimitedException());

		mockMvc.perform(post("/api/v1/answers/10/translation")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetLang\":\"ko\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code", is("TRANSLATION_RATE_LIMITED")));
	}

	@Test
	void providerUnavailableMapsTo503() throws Exception {
		when(translationService.translateAnswer(any(), eq(10L), eq(TargetLanguage.KO)))
			.thenThrow(new TranslationProviderUnavailableException());

		mockMvc.perform(post("/api/v1/answers/10/translation")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetLang\":\"ko\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code", is("TRANSLATION_UNAVAILABLE")));
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
				public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) {
					resolvers.add(new AuthenticationPrincipalArgumentResolver());
				}
			};
		}
	}
}
