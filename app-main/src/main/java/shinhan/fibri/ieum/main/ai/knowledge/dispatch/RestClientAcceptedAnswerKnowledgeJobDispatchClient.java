package shinhan.fibri.ieum.main.ai.knowledge.dispatch;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public class RestClientAcceptedAnswerKnowledgeJobDispatchClient
	implements AcceptedAnswerKnowledgeJobDispatchClient {

	private static final String DISPATCH_PATH =
		"/ai/v1/internal/accepted-answer-jobs/{answerId}/dispatch";

	private final RestClient restClient;

	public RestClientAcceptedAnswerKnowledgeJobDispatchClient(RestClient restClient) {
		this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
	}

	@Override
	public void dispatch(Long answerId) {
		if (answerId == null || answerId <= 0) {
			throw new IllegalArgumentException("answerId must be positive");
		}
		restClient.post()
			.uri(DISPATCH_PATH, answerId)
			.retrieve()
			.onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
				throw new RestClientResponseException(
					"Accepted answer knowledge dispatch returned a non-success status",
					response.getStatusCode(),
					response.getStatusText(),
					response.getHeaders(),
					new byte[0],
					StandardCharsets.UTF_8
				);
			})
			.toBodilessEntity();
	}
}
