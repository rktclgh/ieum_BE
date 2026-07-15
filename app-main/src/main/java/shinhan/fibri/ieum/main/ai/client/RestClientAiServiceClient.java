package shinhan.fibri.ieum.main.ai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
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
		byte[] requestBody = serializeRequest(request);
		byte[] responseBody = restClient.post()
			.uri(path)
			.contentType(MediaType.APPLICATION_JSON)
			.body(requestBody)
			.retrieve()
			.onStatus(HttpStatusCode::is3xxRedirection, (httpRequest, httpResponse) -> {
				int status = httpResponse.getStatusCode().value();
				throw new RestClientResponseException(
					"AI service redirect response rejected",
					status,
					"Redirect",
					new HttpHeaders(),
					new byte[0],
					StandardCharsets.UTF_8
				);
			})
			.body(byte[].class);
		return deserializeResponse(responseBody);
	}

	private byte[] serializeRequest(ReportReviewRequest request) {
		try {
			return objectMapper.writeValueAsBytes(request);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize report review request", exception);
		}
	}

	private ReportReviewResponse deserializeResponse(byte[] responseBody) {
		if (responseBody == null || responseBody.length == 0) {
			throw new HttpMessageConversionException("Report review response body is empty");
		}
		try {
			return objectMapper.readValue(responseBody, ReportReviewResponse.class);
		} catch (IOException exception) {
			throw new HttpMessageConversionException("Failed to decode report review response", exception);
		}
	}
}
