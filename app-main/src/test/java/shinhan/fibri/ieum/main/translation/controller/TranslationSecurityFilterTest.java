package shinhan.fibri.ieum.main.translation.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.config.JsonAccessDeniedHandler;
import shinhan.fibri.ieum.config.JsonAuthenticationEntryPoint;
import shinhan.fibri.ieum.config.SecurityConfig;
import shinhan.fibri.ieum.main.auth.session.CsrfDoubleSubmitFilter;
import shinhan.fibri.ieum.main.auth.session.JwtAuthenticationFilter;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.auth.session.ValidatedAuthSession;
import shinhan.fibri.ieum.main.translation.dto.TranslationResponse;
import shinhan.fibri.ieum.main.translation.service.TargetLanguage;
import shinhan.fibri.ieum.main.translation.service.TranslationService;

@WebMvcTest(TranslationController.class)
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
class TranslationSecurityFilterTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SessionTokenValidator sessionTokenValidator;

	@Autowired
	private TranslationService translationService;

	@Test
	void anonymousTranslateRequestIsRejectedBySecurityChain() throws Exception {
		mockMvc.perform(post("/api/v1/translate")
				.cookie(new MockCookie("csrf_token", "csrf-token"))
				.header("X-CSRF-Token", "csrf-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"hello\",\"targetLang\":\"ko\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code", is("AUTHENTICATION_REQUIRED")));

		verifyNoInteractions(translationService);
	}

	@Test
	void authenticatedTranslateRequestWithCsrfReachesEndpoint() throws Exception {
		when(sessionTokenValidator.validateSession("user-token"))
			.thenReturn(Optional.of(new ValidatedAuthSession(principal(), "user-session")));
		when(translationService.translate(any(), eq("hello"), eq(TargetLanguage.KO)))
			.thenReturn(new TranslationResponse("안녕"));

		mockMvc.perform(post("/api/v1/translate")
				.cookie(new MockCookie("access_token", "user-token"))
				.cookie(new MockCookie("csrf_token", "csrf-token"))
				.header("X-CSRF-Token", "csrf-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"hello\",\"targetLang\":\"ko\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.translatedText", is("안녕")));

		verify(translationService).translate(any(), eq("hello"), eq(TargetLanguage.KO));
	}

	private static AuthenticatedUser principal() {
		return new AuthenticatedUser(42L, "me@example.com", UserRole.user, UserStatus.active);
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
	}
}
