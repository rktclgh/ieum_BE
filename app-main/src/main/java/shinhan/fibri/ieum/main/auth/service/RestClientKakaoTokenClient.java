package shinhan.fibri.ieum.main.auth.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import shinhan.fibri.ieum.main.auth.exception.InvalidSocialTokenException;

public class RestClientKakaoTokenClient implements KakaoTokenClient {

	private final RestClient restClient;
	private final String tokenUri;
	private final String restApiKey;
	private final String clientSecret;

	public RestClientKakaoTokenClient(
		RestClient restClient,
		String tokenUri,
		String restApiKey,
		String clientSecret
	) {
		this.restClient = restClient;
		this.tokenUri = tokenUri;
		this.restApiKey = restApiKey;
		this.clientSecret = clientSecret;
	}

	@Override
	public String exchangeCode(String code, String redirectUri) {
		try {
			KakaoTokenResponse response = restClient.post()
				.uri(tokenUri)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(formBody(code, redirectUri))
				.retrieve()
				.body(KakaoTokenResponse.class);
			if (response == null || response.idToken() == null || response.idToken().isBlank()) {
				throw new InvalidSocialTokenException();
			}
			return response.idToken();
		} catch (RestClientException exception) {
			throw new InvalidSocialTokenException();
		}
	}

	private MultiValueMap<String, String> formBody(String code, String redirectUri) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("client_id", restApiKey);
		form.add("redirect_uri", redirectUri);
		form.add("code", code);
		if (clientSecret != null && !clientSecret.isBlank()) {
			form.add("client_secret", clientSecret);
		}
		return form;
	}

	private record KakaoTokenResponse(
		@JsonProperty("id_token")
		String idToken
	) {
	}
}
