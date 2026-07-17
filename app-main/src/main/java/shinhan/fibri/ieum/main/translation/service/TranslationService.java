package shinhan.fibri.ieum.main.translation.service;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.translation.dto.TranslationResponse;

@Service
@RequiredArgsConstructor
public class TranslationService {

	private static final int MAX_TEXT_CODE_POINTS = 5_000;

	private final TranslationRateLimiter rateLimiter;
	private final TranslationClient translationClient;

	public TranslationResponse translate(
		AuthenticatedUser principal,
		String text,
		TargetLanguage targetLanguage
	) {
		Long userId = requirePrincipal(principal).userId();
		TargetLanguage normalizedTarget = Objects.requireNonNull(targetLanguage, "targetLanguage must not be null");
		String translationText = requireTranslatableText(text);
		if (!rateLimiter.tryAcquire(userId)) {
			throw new TranslationRateLimitedException();
		}
		ProviderTranslationResult result = translationClient.translate(translationText, normalizedTarget);
		return new TranslationResponse(result.translatedText());
	}

	private String requireTranslatableText(String text) {
		if (text == null || text.isBlank()) {
			throw new TranslationNotAvailableException();
		}
		if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) {
			throw new TranslationNotAvailableException();
		}
		return text;
	}

	private AuthenticatedUser requirePrincipal(AuthenticatedUser principal) {
		return Objects.requireNonNull(principal, "principal must not be null");
	}
}
