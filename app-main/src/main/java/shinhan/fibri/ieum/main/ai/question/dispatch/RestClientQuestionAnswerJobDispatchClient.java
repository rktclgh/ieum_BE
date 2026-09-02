package shinhan.fibri.ieum.main.ai.question.dispatch;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 정상 전송 경로는 RabbitMQ다. 이 경로는 {@code transport=http} 롤백 전용이며 별도 이슈(#254)에서
 * 제거된다.
 */
@Deprecated
public class RestClientQuestionAnswerJobDispatchClient implements QuestionAnswerJobDispatchClient {

	private static final String DISPATCH_PATH =
		"/ai/v1/internal/question-answer-jobs/{questionId}/dispatch";

	private final RestClient restClient;

	public RestClientQuestionAnswerJobDispatchClient(RestClient restClient) {
		this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
	}

	@Override
	public void dispatch(Long questionId) {
		if (questionId == null || questionId <= 0) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		restClient.post()
			.uri(DISPATCH_PATH, questionId)
			.retrieve()
			.onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
				throw new RestClientResponseException(
					"Question answer dispatch returned a non-success status",
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
