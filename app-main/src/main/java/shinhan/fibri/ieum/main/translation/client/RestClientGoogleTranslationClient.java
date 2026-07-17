package shinhan.fibri.ieum.main.translation.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
					throw new TranslationProviderUnavailableException();
				})
				.body(GoogleTranslateResponse.class);
			return firstTranslation(response);
		} catch (TranslationProviderUnavailableException exception) {
			throw exception;
		} catch (RestClientException | HttpMessageConversionException | IllegalArgumentException exception) {
			throw new TranslationProviderUnavailableException();
		}
	}

	private ProviderTranslationResult firstTranslation(GoogleTranslateResponse response) {
		if (response == null
			|| response.data() == null
			|| response.data().translations() == null
			|| response.data().translations().isEmpty()) {
			throw new TranslationProviderUnavailableException();
		}
		GoogleTranslation translation = response.data().translations().getFirst();
		if (translation.translatedText() == null || translation.detectedSourceLanguage() == null) {
			throw new TranslationProviderUnavailableException();
		}
		return new ProviderTranslationResult(translation.translatedText(), translation.detectedSourceLanguage());
	}

	private record GoogleTranslateResponse(GoogleTranslateData data) {
	}

	private record GoogleTranslateData(List<GoogleTranslation> translations) {
	}

	private record GoogleTranslation(String translatedText, String detectedSourceLanguage) {
	}
}
