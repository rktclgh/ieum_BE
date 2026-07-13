package shinhan.fibri.ieum.ai.question.callback;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpQuestionCompletionCallbackClient implements QuestionCompletionCallbackClient {

	private static final Logger log = LoggerFactory.getLogger(HttpQuestionCompletionCallbackClient.class);
	private static final String COMPLETION_PATH =
		"/api/v1/internal/ai/question-answer-jobs/%d/completed";
	private static final String INTERNAL_TOKEN_HEADER = "X-IEUM-Internal-Token";

	private final HttpClient httpClient;
	private final QuestionCompletionCallbackProperties properties;

	public HttpQuestionCompletionCallbackClient(
		HttpClient httpClient,
		QuestionCompletionCallbackProperties properties
	) {
		if (httpClient == null || properties == null) {
			throw new IllegalArgumentException("Callback client and properties are required");
		}
		if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
			throw new IllegalArgumentException("Callback HTTP client must never follow redirects");
		}
		this.httpClient = httpClient;
		this.properties = properties;
	}

	@Override
	public CallbackHttpResult deliver(long questionId, long answerId) {
		validatePositive(questionId, "questionId");
		validatePositive(answerId, "answerId");
		HttpRequest request = HttpRequest.newBuilder(callbackUri(questionId))
			.timeout(properties.readTimeout())
			.header("Content-Type", "application/json")
			.header(INTERNAL_TOKEN_HEADER, properties.internalToken())
			.POST(HttpRequest.BodyPublishers.ofString(
				"{\"answerId\":" + answerId + "}",
				StandardCharsets.UTF_8
			))
			.build();
		try {
			HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
			int status = response.statusCode();
			if (status >= 200 && status < 300) {
				return CallbackHttpResult.DELIVERED;
			}
			if (status == 404) {
				log.warn("Question completion callback returned 404 for questionId={}", questionId);
				return CallbackHttpResult.NOT_FOUND;
			}
			log.warn("Question completion callback failed for questionId={} status={}", questionId, status);
			return CallbackHttpResult.FAILED;
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			log.warn("Question completion callback was interrupted for questionId={}", questionId);
			return CallbackHttpResult.FAILED;
		}
		catch (IOException exception) {
			log.warn(
				"Question completion callback transport failed for questionId={} errorType={}",
				questionId,
				exception.getClass().getSimpleName()
			);
			return CallbackHttpResult.FAILED;
		}
	}

	private URI callbackUri(long questionId) {
		return URI.create(properties.baseOrigin() + COMPLETION_PATH.formatted(questionId));
	}

	private static void validatePositive(long value, String field) {
		if (value <= 0) {
			throw new IllegalArgumentException(field + " must be positive");
		}
	}
}
