package shinhan.fibri.ieum.main.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewMessage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;

class RestClientAiServiceClientTest {

	@Test
	void postsTheReviewRequestWithoutApplicationSignatureHeaders() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.example.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientAiServiceClient client = new RestClientAiServiceClient(
			builder.build(),
			new ObjectMapper().findAndRegisterModules()
		);
		ReportReviewRequest request = new ReportReviewRequest(
			900L,
			UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
			500L,
			"harassment",
			"detail",
			"a".repeat(64),
			List.of(new ReportReviewMessage(500L, "reported_user", "abuse", null, "2026-07-11T00:00:00Z"))
		);

		server.expect(requestTo(URI.create("https://ai.example.test/ai/v1/internal/reports/900/review")))
			.andExpect(method(POST))
			.andExpect(headerDoesNotExist("X-Internal-Service"))
			.andExpect(headerDoesNotExist("X-Internal-Key-Id"))
			.andExpect(headerDoesNotExist("X-Internal-Timestamp"))
			.andExpect(headerDoesNotExist("X-Internal-Request-Id"))
			.andExpect(headerDoesNotExist("X-Internal-Body-SHA256"))
			.andExpect(headerDoesNotExist("X-Internal-Signature"))
			.andRespond(withSuccess("""
				{
				  "decision":"normal",
				  "confidence":0.42,
				  "evidence":[],
				  "matchedRules":[],
				  "policySnapshot":{"rules":[]},
				  "fallbackUsed":false,
				  "providerAttempts":[{"provider":"gemini","outcome":"success"}]
				}
				""", MediaType.APPLICATION_JSON));

		ReportReviewResponse response = client.review(request);

		assertThat(response.decision()).isEqualTo("normal");
		assertThat(response.confidence()).isEqualByComparingTo("0.42");
		assertThat(response.evidence().isArray()).isTrue();
		assertThat(response.matchedRules().isArray()).isTrue();
		assertThat(response.policySnapshot().isObject()).isTrue();
		assertThat(response.providerAttempts().isArray()).isTrue();
		server.verify();
	}

	@Test
	void rejectsMalformedSuccessBodiesWithoutExposingTheResponsePayload() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.example.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientAiServiceClient client = new RestClientAiServiceClient(
			builder.build(),
			new ObjectMapper().findAndRegisterModules()
		);
		ReportReviewRequest request = new ReportReviewRequest(
			900L,
			UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
			500L,
			"harassment",
			"detail",
			"a".repeat(64),
			List.of(new ReportReviewMessage(500L, "reported_user", "abuse", null, "2026-07-11T00:00:00Z"))
		);
		server.expect(requestTo(URI.create("https://ai.example.test/ai/v1/internal/reports/900/review")))
			.andRespond(withSuccess("{raw-provider-payload", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.review(request))
			.isInstanceOf(HttpMessageConversionException.class)
			.hasMessageNotContaining("raw-provider-payload");

		server.verify();
	}
}
