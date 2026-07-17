package shinhan.fibri.ieum.main.translation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import shinhan.fibri.ieum.main.translation.service.ProviderTranslationResult;
import shinhan.fibri.ieum.main.translation.service.TargetLanguage;
import shinhan.fibri.ieum.main.translation.service.TranslationProviderUnavailableException;

@ExtendWith(OutputCaptureExtension.class)
class RestClientGoogleTranslationClientTest {

	@Test
	void sendsTextAndTargetOnlyAndReadsFirstTranslation() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://translation.googleapis.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientGoogleTranslationClient client = new RestClientGoogleTranslationClient(builder.build(), "server-key");
		server.expect(requestTo("https://translation.googleapis.com/language/translate/v2?key=server-key"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.q").value("hello"))
			.andExpect(jsonPath("$.target").value("ko"))
			.andExpect(jsonPath("$.source").doesNotExist())
			.andRespond(withSuccess("""
				{"data":{"translations":[{"translatedText":"안녕","detectedSourceLanguage":"en"}]}}
				""", MediaType.APPLICATION_JSON));

		ProviderTranslationResult result = client.translate("hello", TargetLanguage.KO);

		assertThat(result).isEqualTo(new ProviderTranslationResult("안녕"));
		server.verify();
	}

	@Test
	void providerFailureLogsSafeMetadataOnly(CapturedOutput output) {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://translation.googleapis.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientGoogleTranslationClient client = new RestClientGoogleTranslationClient(builder.build(), "server-key");
		server.expect(requestTo("https://translation.googleapis.com/language/translate/v2?key=server-key"))
			.andRespond(withBadRequest().body("""
				{"error":{"message":"bad key or payload for secret text server-key https://translation.googleapis.com/language/translate/v2?key=server-key"}}
				"""));

		assertThatThrownBy(() -> client.translate("secret text", TargetLanguage.KO))
			.isInstanceOf(TranslationProviderUnavailableException.class)
			.hasMessage("Translation provider is unavailable");

		assertThat(output).contains("Google translation provider request failed");
		assertThat(output).contains("provider=google-translate");
		assertThat(output).contains("status=400 BAD_REQUEST");
		assertThat(output).doesNotContain("secret text");
		assertThat(output).doesNotContain("server-key");
		assertThat(output).doesNotContain("bad key or payload");
		assertThat(output).doesNotContain("translation.googleapis.com/language/translate");
	}

	@Test
	void malformedSuccessBodyLogsSafeFixedReasonOnly(CapturedOutput output) {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://translation.googleapis.com");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientGoogleTranslationClient client = new RestClientGoogleTranslationClient(builder.build(), "server-key");
		server.expect(requestTo("https://translation.googleapis.com/language/translate/v2?key=server-key"))
			.andRespond(withSuccess("""
				{"data":{"translations":[]}, "leak":"secret text server-key https://translation.googleapis.com/language/translate/v2?key=server-key"}
				""", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.translate("secret text", TargetLanguage.KO))
			.isInstanceOf(TranslationProviderUnavailableException.class)
			.hasMessage("Translation provider is unavailable");

		assertThat(output).contains("Google translation provider malformed success response");
		assertThat(output).contains("provider=google-translate");
		assertThat(output).contains("reason=missing_translation");
		assertThat(output).doesNotContain("secret text");
		assertThat(output).doesNotContain("server-key");
		assertThat(output).doesNotContain("leak");
		assertThat(output).doesNotContain("translation.googleapis.com/language/translate");
	}
}
