package shinhan.fibri.ieum.main.ai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationRequest;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationResponse;

public class RestClientAiQuestionRecommendationClient implements AiQuestionRecommendationClient {

	private static final String RECOMMENDATION_PATH = "/ai/v1/internal/questions/recommendations";

	private final RestClient restClient;
	private final ObjectMapper objectMapper;

	public RestClientAiQuestionRecommendationClient(RestClient restClient, ObjectMapper objectMapper) {
		this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@Override
	public InternalQuestionRecommendationResponse recommend(InternalQuestionRecommendationRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		try {
			byte[] body = objectMapper.writeValueAsBytes(request);
			return restClient.post()
				.uri(RECOMMENDATION_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(InternalQuestionRecommendationResponse.class);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize question recommendation request", exception);
		}
		catch (HttpStatusCodeException exception) {
			if (exception.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE) {
				throw new AiQuestionRecommendationUnavailableException(
					"AI question recommendation service is unavailable",
					exception
				);
			}
			throw new AiQuestionRecommendationClientException(
				"AI question recommendation service returned HTTP " + exception.getStatusCode().value(),
				exception
			);
		}
		catch (ResourceAccessException exception) {
			if (isTimeout(exception)) {
				throw new AiQuestionRecommendationTimeoutException(
					"AI question recommendation request timed out",
					exception
				);
			}
			throw new AiQuestionRecommendationUnavailableException(
				"AI question recommendation service is unavailable",
				exception
			);
		}
	}

	private boolean isTimeout(Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof HttpTimeoutException
					|| current instanceof InterruptedIOException
					|| current instanceof TimeoutException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
