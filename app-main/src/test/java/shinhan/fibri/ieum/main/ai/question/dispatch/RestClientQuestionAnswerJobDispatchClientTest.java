package shinhan.fibri.ieum.main.ai.question.dispatch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class RestClientQuestionAnswerJobDispatchClientTest {

	@Test
	void postsTheQuestionIdWithoutABodyOrBrowserCredentials() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://app-ai.internal:8081");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientQuestionAnswerJobDispatchClient client = new RestClientQuestionAnswerJobDispatchClient(builder.build());

		server.expect(requestTo(URI.create(
			"http://app-ai.internal:8081/ai/v1/internal/question-answer-jobs/42/dispatch"
		)))
			.andExpect(method(POST))
			.andExpect(content().string(""))
			.andExpect(headerDoesNotExist("Cookie"))
			.andExpect(headerDoesNotExist("Authorization"))
			.andExpect(headerDoesNotExist("X-CSRF-TOKEN"))
			.andExpect(headerDoesNotExist("X-XSRF-TOKEN"))
			.andRespond(withStatus(HttpStatus.ACCEPTED));

		client.dispatch(42L);

		server.verify();
	}

	@Test
	void surfacesNonSuccessSoTheBestEffortListenerCanLogAndDropIt() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://app-ai.internal:8081");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientQuestionAnswerJobDispatchClient client = new RestClientQuestionAnswerJobDispatchClient(builder.build());
		server.expect(requestTo(URI.create(
			"http://app-ai.internal:8081/ai/v1/internal/question-answer-jobs/42/dispatch"
		)))
			.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

		assertThatThrownBy(() -> client.dispatch(42L))
			.isInstanceOf(RestClientResponseException.class);

		server.verify();
	}
}
