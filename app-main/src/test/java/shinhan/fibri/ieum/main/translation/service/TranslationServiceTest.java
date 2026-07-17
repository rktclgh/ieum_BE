package shinhan.fibri.ieum.main.translation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.translation.dto.TranslationResponse;

class TranslationServiceTest {

	private final TranslationRateLimiter rateLimiter = mock(TranslationRateLimiter.class);
	private final TranslationClient translationClient = mock(TranslationClient.class);
	private final TranslationService service = new TranslationService(rateLimiter, translationClient);

	@Test
	void translatesCallerSuppliedTextForAuthenticatedUser() {
		when(rateLimiter.tryAcquire(42L)).thenReturn(true);
		when(translationClient.translate("hello", TargetLanguage.KO))
			.thenReturn(new ProviderTranslationResult("안녕"));

		TranslationResponse response = service.translate(principal(), "hello", TargetLanguage.KO);

		assertThat(response).isEqualTo(new TranslationResponse("안녕"));
		verify(rateLimiter).tryAcquire(42L);
		verify(translationClient).translate("hello", TargetLanguage.KO);
	}

	@Test
	void rejectsAnonymousCallerBeforeRateLimitOrProvider() {
		assertThatNullPointerException()
			.isThrownBy(() -> service.translate(null, "hello", TargetLanguage.KO))
			.withMessage("principal must not be null");

		verify(rateLimiter, never()).tryAcquire(any());
		verify(translationClient, never()).translate(any(), any());
	}

	@Test
	void rejectsBlankTextBeforeRateLimitOrProvider() {
		assertThatThrownBy(() -> service.translate(principal(), "   ", TargetLanguage.KO))
			.isInstanceOf(TranslationNotAvailableException.class);

		verify(rateLimiter, never()).tryAcquire(any());
		verify(translationClient, never()).translate(any(), any());
	}

	@Test
	void rejectsNullTextBeforeRateLimitOrProvider() {
		assertThatThrownBy(() -> service.translate(principal(), null, TargetLanguage.KO))
			.isInstanceOf(TranslationNotAvailableException.class);

		verify(rateLimiter, never()).tryAcquire(any());
		verify(translationClient, never()).translate(any(), any());
	}

	@Test
	void rejectsTooLongTextBeforeRateLimitOrProvider() {
		String longText = "가".repeat(5_001);

		assertThatThrownBy(() -> service.translate(principal(), longText, TargetLanguage.KO))
			.isInstanceOf(TranslationNotAvailableException.class);

		verify(rateLimiter, never()).tryAcquire(any());
		verify(translationClient, never()).translate(any(), any());
	}

	@Test
	void rateLimitedBeforeProvider() {
		when(rateLimiter.tryAcquire(42L)).thenReturn(false);

		assertThatThrownBy(() -> service.translate(principal(), "hello", TargetLanguage.KO))
			.isInstanceOf(TranslationRateLimitedException.class);

		verify(translationClient, never()).translate(any(), any());
	}

	@Test
	void providerUnavailableBubblesToHandler() {
		when(rateLimiter.tryAcquire(42L)).thenReturn(true);
		when(translationClient.translate("hello", TargetLanguage.KO))
			.thenThrow(new TranslationProviderUnavailableException());

		assertThatThrownBy(() -> service.translate(principal(), "hello", TargetLanguage.KO))
			.isInstanceOf(TranslationProviderUnavailableException.class);
	}

	private static AuthenticatedUser principal() {
		return new AuthenticatedUser(42L, "me@example.com", UserRole.user, UserStatus.active);
	}
}
