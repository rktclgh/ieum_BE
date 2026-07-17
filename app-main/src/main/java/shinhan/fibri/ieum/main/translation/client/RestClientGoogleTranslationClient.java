package shinhan.fibri.ieum.main.translation.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import shinhan.fibri.ieum.main.translation.service.ProviderTranslationResult;
import shinhan.fibri.ieum.main.translation.service.TargetLanguage;
import shinhan.fibri.ieum.main.translation.service.TranslationClient;
import shinhan.fibri.ieum.main.translation.service.TranslationProviderUnavailableException;

public class RestClientGoogleTranslationClient implements TranslationClient {

	private static final Logger log = LoggerFactory.getLogger(RestClientGoogleTranslationClient.class);

	private final RestClient restClient;
	private final String apiKey;

	public RestClientGoogleTranslationClient(RestClient restClient, String apiKey) {
		this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
		this.apiKey = apiKey == null ? "" : apiKey.trim();
	}

	@Override
	public ProviderTranslationResult translate(String text, TargetLanguage targetLanguage) {
		if (apiKey.isBlank()) {
			throw new TranslationProviderUnavailableException();
		}
		try {
			GoogleTranslateResponse response = restClient.post()
				.uri(uriBuilder -> uriBuilder.path("/language/translate/v2").queryParam("key", apiKey).build())
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("q", text, "target", targetLanguage.code(), "format", "text"))
				.retrieve()
				.onStatus(status -> status.isError() || status.is3xxRedirection(), (request, providerResponse) -> {
					logProviderStatusFailure(providerResponse.getStatusCode());
					throw new TranslationProviderUnavailableException();
				})
				.body(GoogleTranslateResponse.class);
			return firstTranslation(response);
		} catch (TranslationProviderUnavailableException exception) {
			throw exception;
		} catch (RestClientException | HttpMessageConversionException | IllegalArgumentException exception) {
			logProviderExceptionFailure(exception);
			throw new TranslationProviderUnavailableException();
		}
	}

	private void logProviderStatusFailure(HttpStatusCode statusCode) {
		log.warn("Google translation provider request failed provider=google-translate status={}", statusCode);
	}

	private void logProviderExceptionFailure(Exception exception) {
		log.warn(
			"Google translation provider request failed provider=google-translate exceptionType={}",
			exception.getClass().getSimpleName()
		);
	}

	private void logMalformedSuccessResponse(String reason) {
		log.warn("Google translation provider malformed success response provider=google-translate reason={}", reason);
	}

	private ProviderTranslationResult firstTranslation(GoogleTranslateResponse response) {
		if (response == null
			|| response.data() == null
			|| response.data().translations() == null
			|| response.data().translations().isEmpty()) {
			logMalformedSuccessResponse("missing_translation");
			throw new TranslationProviderUnavailableException();
		}
		GoogleTranslation translation = response.data().translations().getFirst();
		if (translation.translatedText() == null) {
			logMalformedSuccessResponse("missing_translated_text");
			throw new TranslationProviderUnavailableException();
		}
		return new ProviderTranslationResult(translation.translatedText());
	}

	private record GoogleTranslateResponse(GoogleTranslateData data) {
	}

	private record GoogleTranslateData(List<GoogleTranslation> translations) {
	}

	private record GoogleTranslation(String translatedText, String detectedSourceLanguage) {
	}
}
