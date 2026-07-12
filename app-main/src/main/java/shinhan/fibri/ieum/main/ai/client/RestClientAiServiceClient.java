package shinhan.fibri.ieum.main.ai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;

public class RestClientAiServiceClient implements AiServiceClient {

	private final RestClient restClient;
	private final ObjectMapper objectMapper;

	public RestClientAiServiceClient(RestClient restClient, ObjectMapper objectMapper) {
		this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@Override
	public ReportReviewResponse review(ReportReviewRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		String path = "/ai/v1/internal/reports/" + request.reportId() + "/review";
		try {
			byte[] body = objectMapper.writeValueAsBytes(request);
			return restClient.post()
				.uri(path)
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.body(ReportReviewResponse.class);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize report review request", exception);
		}
	}
}
