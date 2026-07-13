package shinhan.fibri.ieum.main.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationRequest;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationResponse;
import shinhan.fibri.ieum.common.ai.question.dto.QuestionRecommendationLocation;

class RestClientAiQuestionRecommendationClientTest {

	@Test
	void postsRecommendationRequestWithoutBrowserOrApplicationCredentials() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.example.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientAiQuestionRecommendationClient client = new RestClientAiQuestionRecommendationClient(
			builder.build(),
			new ObjectMapper().findAndRegisterModules()
		);
		InternalQuestionRecommendationRequest request = request();

		server.expect(requestTo(URI.create("https://ai.example.test/ai/v1/internal/questions/recommendations")))
			.andExpect(method(POST))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().json("""
				{
				  "title": "분실물 찾기",
				  "content": "홍대입구역 근처에서 지갑을 잃어버렸어요.",
				  "location": {
				    "lat": 37.5563,
				    "lng": 126.9236,
				    "address": "서울 마포구",
				    "detailAddress": "홍대입구역",
				    "label": "현재 위치"
				  },
				  "candidateLimit": 3
				}
				"""))
			.andExpect(headerDoesNotExist("Cookie"))
			.andExpect(headerDoesNotExist("Authorization"))
			.andExpect(headerDoesNotExist("X-CSRF-TOKEN"))
			.andExpect(headerDoesNotExist("X-XSRF-TOKEN"))
			.andExpect(headerDoesNotExist("X-Internal-Service"))
			.andExpect(headerDoesNotExist("X-Internal-Signature"))
			.andRespond(withSuccess("""
				{
				  "items": [
				    {
				      "questionId": 10,
				      "authorId": 20,
				      "title": "홍대입구역 지갑",
				      "relevanceScore": 0.98,
				      "geoScope": "nearby",
				      "isResolved": false,
				      "acceptedAnswer": null
				    }
				  ]
				}
				""", MediaType.APPLICATION_JSON));

		InternalQuestionRecommendationResponse response = client.recommend(request);

		assertThat(response.items()).hasSize(1);
		assertThat(response.items().get(0).questionId()).isEqualTo(10L);
		assertThat(response.items().get(0).relevanceScore()).isEqualByComparingTo("0.98");
		server.verify();
	}

	@Test
	void mapsServiceUnavailableWithoutExposingResponseBody() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.example.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientAiQuestionRecommendationClient client = new RestClientAiQuestionRecommendationClient(
			builder.build(),
			new ObjectMapper().findAndRegisterModules()
		);
		server.expect(requestTo(URI.create("https://ai.example.test/ai/v1/internal/questions/recommendations")))
			.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
				.contentType(MediaType.TEXT_PLAIN)
				.body("provider stacktrace"));

		assertThatThrownBy(() -> client.recommend(request()))
			.isInstanceOf(AiQuestionRecommendationUnavailableException.class)
			.hasMessageNotContaining("provider stacktrace");
		server.verify();
	}

	@Test
	void mapsTimeoutResourceAccessToTimeoutException() {
		RestClient restClient = RestClient.builder()
			.requestFactory((uri, method) -> {
				throw new ResourceAccessException("timeout", new HttpTimeoutException("deadline"));
			})
			.build();
		RestClientAiQuestionRecommendationClient client = new RestClientAiQuestionRecommendationClient(
			restClient,
			new ObjectMapper().findAndRegisterModules()
		);

		assertThatThrownBy(() -> client.recommend(request()))
			.isInstanceOf(AiQuestionRecommendationTimeoutException.class)
			.hasMessageContaining("timed out");
	}

	@Test
	void mapsOtherHttpStatusesWithoutExposingResponseBody() {
		RestClient.Builder builder = RestClient.builder().baseUrl("https://ai.example.test");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientAiQuestionRecommendationClient client = new RestClientAiQuestionRecommendationClient(
			builder.build(),
			new ObjectMapper().findAndRegisterModules()
		);
		server.expect(requestTo(URI.create("https://ai.example.test/ai/v1/internal/questions/recommendations")))
			.andRespond(withStatus(HttpStatus.BAD_GATEWAY)
				.contentType(MediaType.TEXT_PLAIN)
				.body("gateway raw body"));

		assertThatThrownBy(() -> client.recommend(request()))
			.isInstanceOf(AiQuestionRecommendationClientException.class)
			.isNotInstanceOf(AiQuestionRecommendationTimeoutException.class)
			.hasMessageNotContaining("gateway raw body");
		server.verify();
	}

	private InternalQuestionRecommendationRequest request() {
		return new InternalQuestionRecommendationRequest(
			"분실물 찾기",
			"홍대입구역 근처에서 지갑을 잃어버렸어요.",
			new QuestionRecommendationLocation(
				new BigDecimal("37.5563"),
				new BigDecimal("126.9236"),
				"서울 마포구",
				"홍대입구역",
				"현재 위치"
			),
			3
		);
	}
}
